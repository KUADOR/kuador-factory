package ec.kuador.board.connections;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.member.application.MemberService;
import ec.kuador.board.member.domain.Member;
import ec.kuador.board.member.domain.MemberKind;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import ec.kuador.board.workspace.domain.Workspace;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Conexiones del negocio de punta a punta contra un servidor MCP de prueba: se conecta, se asigna a un tablero y un rol
 * la usa; lo que escribe queda apagado hasta habilitarlo; solo administra quien administra; otro espacio no la ve.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=", "board.connections.allow-private=true",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class ConnectionsIT {

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        ec.kuador.board.testing.SharedPostgres.register(registry);
        // La credencial de la conexión se guarda cifrada: la prueba trae su propia clave (el CI no tiene BOARD_SECRETS_KEY).
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        registry.add("board.secrets-key", () -> java.util.Base64.getEncoder().encodeToString(key));
    }

    static final Map<String, String> INBOX = new ConcurrentHashMap<>();
    static HttpServer agenda;

    @TestConfiguration
    static class Mail {
        @Bean
        @Primary
        VerificationMailer inbox() {
            return INBOX::put;
        }
    }

    @BeforeAll
    static void startAgenda() throws Exception {
        agenda = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agenda.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("notifications/initialized")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            String id = body.contains("\"id\":1") ? "1" : "2";
            String result = body.contains("\"initialize\"") ? "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"agenda\",\"version\":\"1\"}}"
                    : body.contains("tools/list") ? "{\"tools\":[{\"name\":\"buscar_citas\",\"description\":\"Citas de un día\",\"inputSchema\":{\"type\":\"object\"},"
                            + "\"annotations\":{\"readOnlyHint\":true}},{\"name\":\"crear_cita\",\"description\":\"Agenda una cita\",\"inputSchema\":{\"type\":\"object\"}}]}"
                    : "{\"content\":[{\"type\":\"text\",\"text\":\"3 citas\"}]}";
            byte[] out = ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        agenda.start();
    }

    @AfterAll
    static void stopAgenda() {
        agenda.stop(0);
    }

    @Autowired SignupService signup;
    @Autowired AuthService auth;
    @Autowired MemberService members;
    @Autowired ConnectionsApi connections;
    @Autowired JdbcClient jdbc;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    record Reply(int status, JsonNode body) {}

    private Reply call(String method, String path, Object body, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) req.header("Authorization", "Bearer " + token);
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(), res.body().isBlank() ? null : json.readTree(res.body()));
    }

    private Workspace workspace(String email, String name) {
        signup.requestCode(email);
        return signup.complete(signup.verifyCode(email, INBOX.get(email)), new SignupService.Company(name, null, null, null, null, null), "Sol", "clave-de-sol-123", "FACTORY");
    }

    @Test
    void aCompanyConnectsASystemAndItsAgentsUseItThroughTheBoard() throws Exception {
        Workspace clinic = workspace("sol@clinica.test", "Clinica");
        String owner = auth.login("sol@clinica.test", "clave-de-sol-123").token();
        Member editor = TenantContext.call(clinic.id(), () -> members.create("Edi", MemberKind.HUMAN, "DEV", null, "#cccccc", null));
        TenantContext.run(clinic.id(), () -> auth.grantAccess(editor.id(), "edi@clinica.test", "clave-de-prueba-1"));
        String edi = auth.login("edi@clinica.test", "clave-de-prueba-1").token();
        call("POST", "/api/projects", Map.of("key", "CLI", "name", "Atención", "templateKey", "TASKS"), owner);

        String url = "http://127.0.0.1:" + agenda.getAddress().getPort() + "/mcp";
        Map<String, Object> input = Map.of("name", "Agenda de la clínica", "url", url, "secret", "token-de-la-agenda");
        assertThat(call("PUT", "/api/connections/AGENDA", input, edi).status()).as("solo quien administra").isEqualTo(403);
        Reply saved = call("PUT", "/api/connections/AGENDA", input, owner);
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.body().get("hasSecret").asBoolean()).isTrue();
        assertThat(saved.body().toString()).as("la credencial nunca vuelve").doesNotContain("token-de-la-agenda");
        assertThat(jdbc.sql("select secret from business_connection where conn_key = 'AGENDA'").query(String.class).single())
                .as("cifrada en la base").doesNotContain("token-de-la-agenda");
        assertThat(saved.body().get("tools")).hasSize(2);

        assertThat(call("PUT", "/api/connections/AGENDA/boards", Map.of("boards", List.of(Map.of("projectKey", "CLI", "roles", List.of("ANALYST")))), owner)
                .body().get("boards").get(0).get("projectKey").asString()).isEqualTo("CLI");
        assertThat(call("GET", "/api/projects/CLI/connections", null, owner).body().get(0).get("connection").asString()).isEqualTo("AGENDA");

        TenantContext.run(clinic.id(), () -> {
            assertThat(connections.toolsFor("CLI", "ANALYST")).extracting(ConnectionsApi.Tool::name).containsExactly("agenda__buscar_citas");
            assertThat(connections.toolsFor("CLI", "MARKETING")).isEmpty();
            assertThat(connections.call("CLI", "ANALYST", "agenda__buscar_citas", Map.of("dia", "lunes"), editor.id(), "CLI-1")).isEqualTo("3 citas");
        });

        call("PUT", "/api/connections/AGENDA/writes", Map.of("tools", List.of("crear_cita")), owner);
        TenantContext.run(clinic.id(), () -> assertThat(connections.toolsFor("CLI", "ANALYST")).hasSize(2));

        // Otro espacio no ve la conexión ni sus herramientas, aunque use las mismas claves.
        Workspace other = workspace("ana@otra.test", "Otra");
        String ana = auth.login("ana@otra.test", "clave-de-sol-123").token();
        assertThat(call("GET", "/api/connections", null, ana).body()).isEmpty();
        TenantContext.run(other.id(), () -> assertThat(connections.toolsFor("CLI", "ANALYST")).isEmpty());

        assertThat(call("DELETE", "/api/connections/AGENDA", null, owner).status()).isEqualTo(204);
        TenantContext.run(clinic.id(), () -> assertThat(connections.toolsFor("CLI", "ANALYST")).isEmpty());
        assertThat(jdbc.sql("select count(*) from business_connection_board").query(Long.class).single()).isZero();
    }
}
