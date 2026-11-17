package ec.kuador.board.agents;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Una puerta que revisa un agente, en un espacio que todavía no tiene al Agente Revisor de PR (los espacios creados
 * antes de que existiera el rol): al guardar las etapas se crea, se suma al equipo y trabaja en el tablero.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=", "board.agents.anthropic-api-key=",
        "board.sync.allowed-roots=", "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class GateReviewIT {

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
    @Autowired JdbcClient jdbc;
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
    void anOlderWorkspaceGetsItsReviewerWhenAGateIsReviewedByAnAgent() throws Exception {
        signup.requestCode("vale@puertas.test");
        Workspace w = signup.complete(signup.verifyCode("vale@puertas.test", INBOX.get("vale@puertas.test")),
                new SignupService.Company("Puertas", null, null, null, null, null), "Vale", "clave-de-vale-123", "FACTORY");
        String owner = auth.login("vale@puertas.test", "clave-de-vale-123").token();
        assertThat(call("POST", "/api/projects", Map.of("key", "PUE", "name", "Puertas", "templateKey", "SOFTWARE_FACTORY"), owner).status()).isEqualTo(201);

        // Como un espacio creado antes del rol: sin el agente revisor.
        jdbc.sql("delete from member where workspace_id = ? and role = 'PR_REVIEWER'").param(w.id()).update();

        var stages = json.createArrayNode();
        call("GET", "/api/projects/PUE", null, owner).body().get("stages").forEach(s -> {
            ObjectNode copy = (ObjectNode) s.deepCopy();
            if (s.get("column").asText().equals("PR_REVIEW")) {
                ObjectNode review = copy.putObject("review");
                review.put("mode", "AGENT_ESCALATE");
                review.put("reviewerRole", "PR_REVIEWER");
                review.putArray("sensitive").add("**/db/migration/**");
                review.put("maxChangedLines", 500);
            }
            stages.add(copy);
        });
        Reply saved = call("PUT", "/api/projects/PUE/stages", Map.of("stages", stages, "maxRework", 3), owner);
        assertThat(saved.status()).as(String.valueOf(saved.body())).isEqualTo(200);

        JsonNode reviewer = null;
        for (JsonNode a : call("GET", "/api/projects/PUE/team", null, owner).body()) {
            if (a.get("role").asText().equals("PR_REVIEWER") && a.get("inTeam").asBoolean()) reviewer = a;
        }
        assertThat(reviewer).as("el revisor está en el equipo del tablero").isNotNull();
        assertThat(reviewer.get("mode").asText()).isEqualTo("SERVER");

        // Guardar otra vez no duplica nada.
        assertThat(call("PUT", "/api/projects/PUE/stages", Map.of("stages", stages, "maxRework", 3), owner).status()).isEqualTo(200);
    }
}
