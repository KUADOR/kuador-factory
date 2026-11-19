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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Servidor MCP por HTTP, como lo usa Claude Code: conexión de una persona, cola de sus agentes y un issue de punta a punta. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class McpIT {

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        ec.kuador.board.testing.SharedPostgres.register(registry);
    }

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
                new SignupService.Company("Pi", null, null, null, null, null), "Pía", "clave-de-pia-123", "FACTORY");
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
        assertThat(init.get("serverInfo").get("name").asString()).as("se presenta con la empresa, no con el producto").isEqualTo("pi");
        assertThat(init.get("instructions").asString()).contains("Tablero de Pi");
        assertThat(call("POST", "/mcp", Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), runner).status()).isEqualTo(202);
        List<String> names = new ArrayList<>();
        rpc(runner, "tools/list", Map.of()).body().get("result").get("tools").forEach(t -> names.add(t.get("name").asString()));
        assertThat(names).contains("pending_work", "get_issue", "role_manual", "take_issue", "add_comment", "deliver", "send_back", "ask_human",
                "read_document", "write_document");
        // El prompt «pendientes» (en Claude Code, /mcp__pi__pendientes) trae los pasos sin archivos en el repo.
        assertThat(init.get("capabilities").has("prompts")).isTrue();
        assertThat(rpc(runner, "prompts/list", Map.of()).body().get("result").get("prompts").get(0).get("name").asString()).isEqualTo("pendientes");
        String prompt = rpc(runner, "prompts/get", Map.of("name", "pendientes", "arguments", Map.of("filtro", "PI"))).body()
                .get("result").get("messages").get(0).get("content").get("text").asString();
        assertThat(prompt).contains("mcp__pi__pending_work", "«PI»", "subagent");
        assertThat(rpc(runner, "nada/que/ver", Map.of()).body().get("error").get("code").asInt()).isEqualTo(-32601);

        // Pía crea un issue en Refinamiento (lo cubre el agente PO): pasa a su cola.
        call("POST", "/api/projects", Map.of("key", "PI", "name", "Pi", "templateKey", "SOFTWARE_FACTORY"), owner);
        call("POST", "/api/projects/PI/people", Map.of("memberId", call("GET", "/api/auth/me", null, other).body().get("id").asString()), owner);
        String key = call("POST", "/api/projects/PI/issues", Map.of("title", "Agenda de citas", "type", "STORY", "status", "REFINEMENT"), owner)
                .body().get("key").asString();
        JsonNode queue = tool(runner, "pending_work", Map.of(), false);
        assertThat(queue.get("total").asInt()).isEqualTo(1);
        assertThat(queue.get("items").get(0).get("subagent").asString()).isEqualTo("pi-product-owner");
        assertThat(tool(otherRunner, "pending_work", Map.of(), false).get("total").asInt()).as("la cola de otra persona").isZero();

        JsonNode manual = tool(runner, "role_manual", Map.of("agent", "PRODUCT_OWNER"), false);
        assertThat(manual.get("instructions").asString()).contains("Product Owner");
        assertThat(manual.get("languages").get("code").asString()).isEqualTo("en");
        assertThat(manual.get("factoryRules").toString()).contains("el código va en inglés", "van en español");
        assertThat(tool(runner, "get_issue", Map.of("key", key), false).get("project").has("goal")).as("sin objetivo todavía").isFalse();
        // El objetivo del tablero viaja con cada tarjeta: lo escribe una persona que edita.
        assertThat(call("PUT", "/api/projects/PI/goal", Map.of("goal", "Que la clínica agende en línea antes de marzo; simple para pacientes mayores."),
                owner).body().get("goal").asString()).startsWith("Que la clínica");
        assertThat(call("PUT", "/api/projects/PI/goal", Map.of("goal", "x".repeat(1001)), owner).status()).isEqualTo(422);
        assertThat(call("GET", "/api/projects/PI/goal", null, owner).body().get("goal").asString()).contains("pacientes mayores");
        JsonNode detail = tool(runner, "get_issue", Map.of("key", key), false);
        assertThat(detail.get("project").get("goal").asString()).contains("pacientes mayores");
        assertThat(detail.get("stage").get("next").get("column").asString()).isEqualTo("APPROVAL");

        assertThat(tool(otherRunner, "take_issue", Map.of("key", key, "agent", "PRODUCT_OWNER"), true).asString()).contains("lo supervisa Pía");
        assertThat(tool(runner, "take_issue", Map.of("key", key, "agent", "QA"), true).asString()).contains("cubren");
        assertThat(tool(runner, "take_issue", Map.of("key", key, "agent", "NADIE"), true).asString()).contains("No hay un agente con el rol NADIE");
        tool(runner, "take_issue", Map.of("key", key, "agent", "PRODUCT_OWNER"), false);
        JsonNode working = call("GET", "/api/agents/runs?issueKey=" + key, null, owner).body().get(0);
        assertThat(working.get("status").asString()).as("el tablero ve que el agente está trabajando").isEqualTo("RUNNING");
        assertThat(working.get("model").asString()).as("y desde qué cliente (el de Java en esta prueba)").isEqualTo("local:java-http-client");
        tool(runner, "add_comment", Map.of("key", key, "agent", "PRODUCT_OWNER", "text", "Historia y criterios listos."), false);
        assertThat(tool(runner, "estimate", Map.of("key", key, "agent", "PRODUCT_OWNER", "points", "4"), true).asString()).contains("escala");
        assertThat(tool(runner, "estimate", Map.of("key", key, "agent", "PRODUCT_OWNER", "points", "5"), false).get("ok").asBoolean()).isTrue();
        assertThat(tool(runner, "estimate", Map.of("key", key, "agent", "PRODUCT_OWNER", "points", "8"), false).get("ok").asBoolean())
                .as("no pisa una estimación").isFalse();
        assertThat(tool(runner, "write_document", Map.of("key", key, "agent", "PRODUCT_OWNER", "type", "SPEC", "content", "# Spec"), true)
                .asString()).contains("no tiene metodología");
        JsonNode delivered = tool(runner, "deliver", Map.of("key", key, "agent", "PRODUCT_OWNER", "comment", "Listo para aprobar."), false);
        assertThat(delivered.get("column").asString()).isEqualTo("APPROVAL");
        assertThat(call("GET", "/api/agents/runs?issueKey=" + key, null, owner).body().get(0).get("status").asString()).isEqualTo("SUCCEEDED");

        // En la puerta espera a una persona; al aprobar, sigue en la cola de Pía para el arquitecto.
        assertThat(tool(runner, "pending_work", Map.of(), false).get("total").asInt()).isZero();
        call("POST", "/api/issues/" + key + "/review", Map.of("approve", true), owner);
        JsonNode next = tool(runner, "pending_work", Map.of("agent", "ARCHITECT"), false);
        assertThat(next.get("items").get(0).get("stage").asString()).isEqualTo("Diseño");

        JsonNode comments = call("GET", "/api/issues/" + key + "/comments", null, owner).body();
        List<String> bodies = new ArrayList<>();
        comments.forEach(c -> bodies.add(c.get("body").asString()));
        assertThat(bodies).contains("Historia y criterios listos.", "Listo para aprobar.");

        // Kit para el repo, en el formato de cada cliente y con el nombre de la empresa. Con la misma conexión.
        Reply zip = call("GET", "/api/agent-kit.zip?client=claude-code&mcp=CONTEXT7", null, runner);
        assertThat(zip.status()).isEqualTo(200);
        List<String> entries = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.raw()))) {
            for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) entries.add(e.getName());
        }
        assertThat(entries).contains(".mcp.json", ".claude/commands/pendientes.md", ".claude/agents/pi-backend.md", ".claude/agents/pi-qa.md");
        JsonNode claude = call("GET", "/api/agent-kit?client=claude-code&mcp=CONTEXT7", null, owner).body();
        assertThat(claude.get("connection").get("tokenVar").asString()).isEqualTo("TABLERO_PI_TOKEN");
        String mcpJson = claude.get("files").get(0).get("content").asString();
        assertThat(mcpJson).as("sin token: cada persona inicia sesión").contains("\"pi\"", "/mcp", "context7").doesNotContain(runner, "Authorization")
                .doesNotContainIgnoringCase("kuador");
        JsonNode codex = call("GET", "/api/agent-kit?client=codex", null, owner).body().get("files");
        assertThat(codex.get(0).get("path").asString()).isEqualTo(".codex/config.toml");
        assertThat(codex.get(0).get("content").asString()).contains("[mcp_servers.pi]", "codex mcp login pi").doesNotContain("bearer_token_env_var");
        assertThat(codex.get(1).get("path").asString()).isEqualTo("AGENTS.md");
        assertThat(codex.get(1).get("content").asString()).contains("# Tablero de Pi", "role_manual", "el código va en inglés").doesNotContainIgnoringCase("kuador");
        JsonNode generic = call("GET", "/api/agent-kit?client=mcp", null, owner).body().get("files");
        assertThat(generic.get(0).get("content").asString()).contains("/mcp").doesNotContain("Authorization");

        // Revocar la conexión la deja sin acceso.
        String tokenId = issued.body().get("info").get("id").asString();
        assertThat(call("DELETE", "/api/auth/runner-tokens/" + tokenId, null, owner).status()).isEqualTo(204);
        assertThat(rpc(runner, "ping", Map.of()).status()).isEqualTo(401);
    }
}
