package ec.kuador.board.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.member.application.MemberService;
import ec.kuador.board.member.domain.Member;
import ec.kuador.board.member.domain.MemberKind;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import ec.kuador.board.workspace.domain.Workspace;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Servidor MCP por HTTP, como lo usa Claude Code: conexión de una persona, cola de sus agentes y un issue de punta a punta. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=", "board.agents.anthropic-api-key=",
        "board.sync.allowed-roots=", "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class McpIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static final Map<String, String> INBOX = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Mail {
        @Bean
        @Primary
        VerificationMailer inbox() {
            return INBOX::put;
        }
    }

    @Autowired SignupService signup;
    @Autowired AuthService auth;
    @Autowired MemberService members;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private int ids;

    record Reply(int status, JsonNode body, byte[] raw) {}

    private Reply call(String method, String path, Object body, String token, String... headers) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) req.header("Authorization", "Bearer " + token);
        for (int i = 0; i < headers.length; i += 2) req.header(headers[i], headers[i + 1]);
        HttpResponse<byte[]> res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        String text = new String(res.body());
        boolean isJson = res.headers().firstValue("Content-Type").orElse("").contains("json") && !text.isBlank();
        return new Reply(res.statusCode(), isJson ? json.readTree(text) : null, res.body());
    }

    private Reply rpc(String token, String method, Object params) throws Exception {
        return call("POST", "/mcp", Map.of("jsonrpc", "2.0", "id", ++ids, "method", method, "params", params), token);
    }

    /** Llama una herramienta y devuelve el texto del resultado; {@code error} indica si se espera isError. */
    private JsonNode tool(String token, String name, Map<String, String> args, boolean error) throws Exception {
        JsonNode result = rpc(token, "tools/call", Map.of("name", name, "arguments", args)).body().get("result");
        assertThat(result.get("isError").asBoolean()).as(name + ": " + result.get("content").get(0).get("text").asString()).isEqualTo(error);
        String text = result.get("content").get(0).get("text").asString();
        return error ? json.getNodeFactory().stringNode(text) : json.readTree(text);
    }

    private String person(Workspace w, String name, String email) {
        Member m = TenantContext.call(w.id(), () -> members.create(name, MemberKind.HUMAN, "DEV", null, "#cccccc", null));
        TenantContext.run(w.id(), () -> auth.grantAccess(m.id(), email, "clave-de-prueba-1"));
        return auth.login(email, "clave-de-prueba-1").token();
    }

    @Test
    void claudeCodeWorksTheQueueOfItsPersonAsTheWorkspaceAgents() throws Exception {
        signup.requestCode("pia@pi.test");
        Workspace w = signup.complete(signup.verifyCode("pia@pi.test", INBOX.get("pia@pi.test")),
                new SignupService.Company("Pi", null, null, null, null, null), "Pía", "clave-de-pia-123");
        String owner = auth.login("pia@pi.test", "clave-de-pia-123").token();
        String other = person(w, "Otro", "otro@pi.test");

        Reply issued = call("POST", "/api/auth/runner-tokens", Map.of("name", "MacBook de Pía"), owner);
        assertThat(issued.status()).isEqualTo(201);
        String runner = issued.body().get("token").asString();
        assertThat(runner).startsWith("kfr_");
        String otherRunner = call("POST", "/api/auth/runner-tokens", Map.of("name", "PC de Otro"), other).body().get("token").asString();

        // La conexión solo abre el servidor MCP (y el kit); una sesión web no abre el MCP.
        assertThat(call("GET", "/api/projects", null, runner).status()).isEqualTo(401);
        assertThat(rpc(owner, "ping", Map.of()).status()).isEqualTo(403);
        assertThat(call("POST", "/mcp", Map.of("jsonrpc", "2.0", "id", 1, "method", "ping"), runner, "Origin", "https://malo.test").status()).isEqualTo(403);

        JsonNode init = rpc(runner, "initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "claude-code", "version", "2"))).body().get("result");
        assertThat(init.get("protocolVersion").asString()).isEqualTo("2025-06-18");
        assertThat(init.get("serverInfo").get("name").asString()).isEqualTo("kuador-board");
        assertThat(call("POST", "/mcp", Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), runner).status()).isEqualTo(202);
        List<String> names = new ArrayList<>();
        rpc(runner, "tools/list", Map.of()).body().get("result").get("tools").forEach(t -> names.add(t.get("name").asString()));
        assertThat(names).contains("pendientes", "ver_issue", "instrucciones_rol", "tomar", "comentar", "entregar", "devolver", "consultar",
                "leer_documento", "escribir_documento");
        assertThat(rpc(runner, "nada/que/ver", Map.of()).body().get("error").get("code").asInt()).isEqualTo(-32601);

        // Pía crea un issue en Refinamiento (lo cubre el agente PO): pasa a su cola.
        call("POST", "/api/projects", Map.of("key", "PI", "name", "Pi", "templateKey", "SOFTWARE_FACTORY"), owner);
        String key = call("POST", "/api/projects/PI/issues", Map.of("title", "Agenda de citas", "type", "STORY", "status", "REFINEMENT"), owner)
                .body().get("key").asString();
        JsonNode queue = tool(runner, "pendientes", Map.of(), false);
        assertThat(queue.get("total").asInt()).isEqualTo(1);
        assertThat(queue.get("pendientes").get(0).get("subagente").asString()).isEqualTo("kuador-product-owner");
        assertThat(tool(otherRunner, "pendientes", Map.of(), false).get("total").asInt()).as("la cola de otra persona").isZero();

        JsonNode manual = tool(runner, "instrucciones_rol", Map.of("agente", "PRODUCT_OWNER"), false);
        assertThat(manual.get("instrucciones").asString()).contains("Product Owner");
        JsonNode detail = tool(runner, "ver_issue", Map.of("clave", key), false);
        assertThat(detail.get("etapa").get("siguiente").get("columna").asString()).isEqualTo("APPROVAL");

        assertThat(tool(otherRunner, "tomar", Map.of("clave", key, "agente", "PRODUCT_OWNER"), true).asString()).contains("lo supervisa Pía");
        assertThat(tool(runner, "tomar", Map.of("clave", key, "agente", "QA"), true).asString()).contains("cubren");
        assertThat(tool(runner, "tomar", Map.of("clave", key, "agente", "NADIE"), true).asString()).contains("No hay un agente con el rol NADIE");
        tool(runner, "tomar", Map.of("clave", key, "agente", "PRODUCT_OWNER"), false);
        assertThat(call("GET", "/api/agents/runs?issueKey=" + key, null, owner).body().get(0).get("status").asString())
                .as("el tablero ve que el agente está trabajando").isEqualTo("RUNNING");
        tool(runner, "comentar", Map.of("clave", key, "agente", "PRODUCT_OWNER", "texto", "Historia y criterios listos."), false);
        assertThat(tool(runner, "escribir_documento", Map.of("clave", key, "agente", "PRODUCT_OWNER", "tipo", "SPEC", "contenido", "# Spec"), true)
                .asString()).contains("no tiene metodología");
        JsonNode delivered = tool(runner, "entregar", Map.of("clave", key, "agente", "PRODUCT_OWNER", "comentario", "Listo para aprobar."), false);
        assertThat(delivered.get("columna").asString()).isEqualTo("APPROVAL");
        assertThat(call("GET", "/api/agents/runs?issueKey=" + key, null, owner).body().get(0).get("status").asString()).isEqualTo("SUCCEEDED");

        // En la puerta espera a una persona; al aprobar, sigue en la cola de Pía para el arquitecto.
        assertThat(tool(runner, "pendientes", Map.of(), false).get("total").asInt()).isZero();
        call("POST", "/api/issues/" + key + "/review", Map.of("approve", true), owner);
        JsonNode next = tool(runner, "pendientes", Map.of("agente", "ARCHITECT"), false);
        assertThat(next.get("pendientes").get(0).get("etapa").asString()).isEqualTo("Diseño");

        JsonNode comments = call("GET", "/api/issues/" + key + "/comments", null, owner).body();
        List<String> bodies = new ArrayList<>();
        comments.forEach(c -> bodies.add(c.get("body").asString()));
        assertThat(bodies).contains("Historia y criterios listos.", "Listo para aprobar.");

        // Kit para el repo: con la misma conexión.
        Reply zip = call("GET", "/api/claude-kit.zip?mcp=CONTEXT7", null, runner);
        assertThat(zip.status()).isEqualTo(200);
        List<String> entries = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.raw()))) {
            for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) entries.add(e.getName());
        }
        assertThat(entries).contains(".mcp.json", ".claude/commands/pendientes.md", ".claude/agents/kuador-backend.md", ".claude/agents/kuador-qa.md");
        JsonNode files = call("GET", "/api/claude-kit?mcp=CONTEXT7", null, owner).body();
        String mcpJson = files.get(0).get("content").asString();
        assertThat(mcpJson).contains("${KUADOR_TOKEN}", "/mcp", "context7").doesNotContain(runner);

        // Revocar la conexión la deja sin acceso.
        String tokenId = issued.body().get("info").get("id").asString();
        assertThat(call("DELETE", "/api/auth/runner-tokens/" + tokenId, null, owner).status()).isEqualTo(204);
        assertThat(rpc(runner, "ping", Map.of()).status()).isEqualTo(401);
    }
}
