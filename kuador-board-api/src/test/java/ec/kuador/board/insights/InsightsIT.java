package ec.kuador.board.insights;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * El dashboard de punta a punta: una tarjeta con puntos que trabajó un agente llega a «Hecho»; el resumen de la empresa
 * cuenta la entrega, el tiempo de entrega, las horas ahorradas (puntos × horas por punto − lo que tardó el agente) y en
 * qué columna se fue el tiempo. «Mi trabajo» lo ve cada persona; el resumen, solo quien administra.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class InsightsIT {

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

    @Test
    void theOwnerSeesDeliveriesSavedHoursAndWhereTimeGoesAndEachPersonSeesTheirWork() throws Exception {
        signup.requestCode("eva@ins.test");
        Workspace w = signup.complete(signup.verifyCode("eva@ins.test", INBOX.get("eva@ins.test")),
                new SignupService.Company("Ins", null, null, null, null, null), "Eva", "clave-de-eva-123", "FACTORY");
        String owner = auth.login("eva@ins.test", "clave-de-eva-123").token();
        Member dev = TenantContext.call(w.id(), () -> members.create("Leo", MemberKind.HUMAN, "DEV", null, "#cccccc", null));
        TenantContext.run(w.id(), () -> auth.grantAccess(dev.id(), "leo@ins.test", "clave-de-prueba-1"));
        String leo = auth.login("leo@ins.test", "clave-de-prueba-1").token();

        call("POST", "/api/projects", Map.of("key", "INS", "name", "Ins", "templateKey", "TASKS"), owner);
        call("POST", "/api/projects/INS/people", Map.of("memberId", dev.id()), owner);
        JsonNode project = call("GET", "/api/projects/INS", null, owner).body();
        String first = project.get("columns").get(0).get("key").asString();
        String key = call("POST", "/api/projects/INS/issues", Map.of("title", "Campaña de verano", "type", "TASK", "status", first, "storyPoints", 3), owner)
                .body().get("key").asString();
        String agent = null;
        for (JsonNode m : call("GET", "/api/members", null, owner).body()) {
            if ("AGENT".equals(m.get("kind").asString())) agent = m.get("id").asString();
        }
        // La trabajó un agente media hora (su ejecución), y después la tomó Leo.
        call("PUT", "/api/issues/" + key + "/assignee", Map.of("assigneeId", agent), owner);
        Instant now = Instant.now();
        jdbc.sql("""
                insert into agent_run (id, workspace_id, agent_id, issue_key, project_key, trigger_event_id, status, model, input_tokens, output_tokens,
                                       created_at, finished_at)
                values (?, ?, ?, ?, 'INS', ?, 'SUCCEEDED', 'local:runner', 0, 0, ?, ?)""")
                .params(UUID.randomUUID(), w.id(), UUID.fromString(agent), key, UUID.randomUUID(), Timestamp.from(now.minus(40, ChronoUnit.MINUTES)),
                        Timestamp.from(now.minus(10, ChronoUnit.MINUTES)))
                .update();
        call("PUT", "/api/issues/" + key + "/assignee", Map.of("assigneeId", dev.id()), owner);

        // Leo ve la tarjeta en «Mi trabajo».
        JsonNode mine = call("GET", "/api/insights/me", null, leo).body();
        assertThat(mine.get("assigned").get(0).get("key").asString()).isEqualTo(key);

        // Avanza por las columnas (aprobando la puerta) hasta «Hecho».
        for (int i = 0; i < 8; i++) {
            JsonNode issue = call("GET", "/api/issues/" + key, null, owner).body();
            String status = issue.get("status").asString();
            int at = 0;
            for (int c = 0; c < project.get("columns").size(); c++) if (project.get("columns").get(c).get("key").asString().equals(status)) at = c;
            JsonNode col = project.get("columns").get(at);
            if ("DONE".equals(col.get("category").asString())) break;
            boolean gate = false;
            for (JsonNode st : project.get("stages")) if (st.get("column").asString().equals(status) && st.get("gate").asBoolean()) gate = true;
            if (gate) call("POST", "/api/issues/" + key + "/review", Map.of("approve", true), owner);
            else call("POST", "/api/issues/" + key + "/move", Map.of("status", project.get("columns").get(at + 1).get("key").asString(), "index", 0), owner);
        }

        // Solo quien administra ve el resumen de la empresa.
        assertThat(call("GET", "/api/insights/company", null, leo).status()).isEqualTo(403);
        JsonNode c = call("GET", "/api/insights/company?days=30", null, owner).body();
        assertThat(c.get("done").get("value").asDouble()).isEqualTo(1.0);
        assertThat(c.get("leadTimeHours").get("value").isNull()).isFalse();
        // 3 puntos × 4 h por defecto = 12 h; el agente tardó 0,5 h: se ahorraron 11,5 h.
        assertThat(c.get("savings").get("cards").asInt()).isEqualTo(1);
        assertThat(c.get("savings").get("estimatedHours").asDouble()).isEqualTo(12.0);
        assertThat(c.get("hoursSaved").get("value").asDouble()).isEqualTo(11.5);
        assertThat(c.get("recent").get(0).get("key").asString()).isEqualTo(key);
        assertThat(c.get("recent").get(0).get("agentsHelped").asBoolean()).isTrue();
        assertThat(c.get("boards").get(0).get("done").asInt()).isEqualTo(1);
        assertThat(c.get("weeks").size()).isEqualTo(8);
        assertThat(c.get("flow").get(0).get("projectKey").asString()).isEqualTo("INS");

        // Con 6 horas por punto, el ahorro sube; y es un ajuste de quien administra.
        assertThat(call("PUT", "/api/insights/settings", Map.of("hoursPerPoint", 6), leo).status()).isEqualTo(403);
        assertThat(call("PUT", "/api/insights/settings", Map.of("hoursPerPoint", 0), owner).status()).isEqualTo(422);
        assertThat(call("PUT", "/api/insights/settings", Map.of("hoursPerPoint", 6), owner).body().get("hoursPerPoint").asDouble()).isEqualTo(6.0);
        assertThat(call("GET", "/api/insights/company", null, owner).body().get("hoursSaved").get("value").asDouble()).isEqualTo(17.5);
    }
}
