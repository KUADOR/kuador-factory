package ec.kuador.board.auth;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.member.MemberAccess;
import ec.kuador.board.member.application.MemberService;
import ec.kuador.board.member.domain.Member;
import ec.kuador.board.member.domain.MemberKind;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import ec.kuador.board.workspace.domain.Workspace;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

/**
 * Acceso de solo lectura por HTTP: un cliente mira el tablero y comenta, no puede cambiar nada y sí
 * cierra las puertas donde su rol es aprobador.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=", "board.agents.anthropic-api-key=",
        "board.sync.allowed-roots=", "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class AccessIT {

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

    record Reply(int status, JsonNode body) {}

    private Reply call(String method, String path, Object body, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) req.header("Authorization", "Bearer " + token);
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(), res.body().isBlank() ? null : json.readTree(res.body()));
    }

    @Test
    void aViewOnlyClientWatchesAndOnlyClosesItsGates() throws Exception {
        signup.requestCode("olga@omega.test");
        String signupToken = signup.verifyCode("olga@omega.test", INBOX.get("olga@omega.test"));
        Workspace w = signup.complete(signupToken, new SignupService.Company("Omega", null, null, null, null, null), "Olga", "clave-de-olga-123");
        String owner = auth.login("olga@omega.test", "clave-de-olga-123").token();

        // Un cliente nuevo queda en solo lectura por defecto.
        Member client = TenantContext.call(w.id(), () -> members.create("Cliente Omega", MemberKind.HUMAN, "CLIENTE", null, "#cccccc", null));
        assertThat(client.access()).isEqualTo(MemberAccess.VIEW);
        TenantContext.run(w.id(), () -> auth.grantAccess(client.id(), "cliente@omega.test", "clave-del-cliente-1"));
        String viewer = auth.login("cliente@omega.test", "clave-del-cliente-1").token();

        assertThat(call("POST", "/api/projects", Map.of("key", "OME", "name", "Omega", "templateKey", "SOFTWARE_FACTORY"), owner).status()).isEqualTo(201);
        var stages = call("GET", "/api/projects/OME", null, owner).body().get("stages");
        var withClient = json.createArrayNode();
        stages.forEach(s -> {
            var copy = (tools.jackson.databind.node.ObjectNode) s.deepCopy();
            if (s.get("column").asText().equals("APPROVAL")) copy.putArray("approvers").add("OWNER").add("CLIENTE");
            withClient.add(copy);
        });
        assertThat(call("PUT", "/api/projects/OME/stages", Map.of("stages", withClient, "maxRework", 3), owner).status()).isEqualTo(200);
        String gated = call("POST", "/api/projects/OME/issues", Map.of("title", "Agenda", "type", "STORY", "status", "APPROVAL"), owner).body().get("key").asText();
        String other = call("POST", "/api/projects/OME/issues", Map.of("title", "Pagos", "type", "STORY", "status", "DEVELOPMENT"), owner).body().get("key").asText();

        // Mira todo.
        assertThat(call("GET", "/api/auth/me", null, viewer).body().get("access").asText()).isEqualTo("VIEW");
        assertThat(call("GET", "/api/projects/OME/issues", null, viewer).status()).isEqualTo(200);
        assertThat(call("GET", "/api/issues/" + other + "/comments", null, viewer).status()).isEqualTo(200);

        // No escribe.
        Reply create = call("POST", "/api/projects/OME/issues", Map.of("title", "Intruso", "type", "TASK"), viewer);
        assertThat(create.status()).isEqualTo(403);
        assertThat(create.body().get("detail").asText()).contains("solo lectura");
        assertThat(call("POST", "/api/issues/" + other + "/comments", Map.of("body", "¿Esto incluye pagos con tarjeta?"), viewer).status())
                .as("comentar sí puede").isEqualTo(201);
        assertThat(call("POST", "/api/auth/runner-tokens", Map.of("name", "PC"), viewer).status()).as("no pone agentes a trabajar").isEqualTo(403);
        assertThat(call("POST", "/api/issues/" + other + "/move", Map.of("status", "QA", "position", 0), viewer).status()).isEqualTo(403);
        assertThat(call("POST", "/api/issues/" + other + "/take", null, viewer).status()).isEqualTo(403);
        assertThat(call("PUT", "/api/projects/OME/stages", Map.of("stages", withClient, "maxRework", 3), viewer).status()).isEqualTo(403);
        assertThat(call("PUT", "/api/members/" + client.id() + "/access", Map.of("access", "EDIT"), viewer).status()).isEqualTo(403);

        // Pero cierra la puerta donde su rol aprueba, con su comentario.
        Reply approve = call("POST", "/api/issues/" + gated + "/review", Map.of("approve", true, "comment", "De acuerdo"), viewer);
        assertThat(approve.status()).isEqualTo(200);
        assertThat(approve.body().get("status").asText()).isEqualTo("DESIGN");
        assertThat(call("POST", "/api/auth/logout", null, viewer).status()).isEqualTo(204);

        // Quien edita da o quita el acceso; nadie cambia el suyo y siempre queda alguien que edita.
        UUIDs ids = new UUIDs(call("GET", "/api/auth/me", null, owner).body().get("id").asText(), client.id().toString());
        assertThat(call("PUT", "/api/members/" + ids.client() + "/access", Map.of("access", "EDIT"), owner).body().get("access").asText()).isEqualTo("EDIT");
        assertThat(call("PUT", "/api/members/" + ids.owner() + "/access", Map.of("access", "VIEW"), owner).status()).as("nadie cambia el suyo").isEqualTo(422);
    }

    record UUIDs(String owner, String client) {}
}
