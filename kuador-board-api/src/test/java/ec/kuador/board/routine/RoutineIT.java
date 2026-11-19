package ec.kuador.board.routine;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.member.application.MemberService;
import ec.kuador.board.member.domain.Member;
import ec.kuador.board.member.domain.MemberKind;
import ec.kuador.board.routine.application.RoutineService;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Rutinas de punta a punta: ejecutar ahora, no apilar, a su hora y por webhook; las configura una persona que edita. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class RoutineIT {

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
    @Autowired RoutineService routines;
    @Autowired JdbcClient jdbc;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    record Reply(int status, JsonNode body) {}

    private Reply call(String method, String path, Object body, String token, String... headers) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) req.header("Authorization", "Bearer " + token);
        for (int i = 0; i < headers.length; i += 2) req.header(headers[i], headers[i + 1]);
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(), res.body().isBlank() ? null : json.readTree(res.body()));
    }

    @Test
    void routinesCreateTheirCardOnTimeOnDemandAndByWebhookWithoutPilingUp() throws Exception {
        signup.requestCode("sol@rut.test");
        Workspace w = signup.complete(signup.verifyCode("sol@rut.test", INBOX.get("sol@rut.test")),
                new SignupService.Company("Rut", null, null, null, null, null), "Sol", "clave-de-sol-123", "FACTORY");
        String owner = auth.login("sol@rut.test", "clave-de-sol-123").token();
        Member viewer = TenantContext.call(w.id(), () -> members.create("Vio", MemberKind.HUMAN, "DEV", null, "#cccccc", null));
        TenantContext.run(w.id(), () -> auth.grantAccess(viewer.id(), "vio@rut.test", "clave-de-prueba-1"));
        call("PUT", "/api/members/" + viewer.id() + "/access", Map.of("access", "VIEW"), owner);
        String vio = auth.login("vio@rut.test", "clave-de-prueba-1").token();

        call("POST", "/api/projects", Map.of("key", "VEN", "name", "Ventas", "templateKey", "TASKS"), owner);
        String done = null;
        for (JsonNode c : call("GET", "/api/projects/VEN", null, owner).body().get("columns")) if ("DONE".equals(c.get("category").asString())) done = c.get("key").asString();

        Map<String, Object> input = Map.of("title", "Seguimiento de prospectos · {fecha}", "description", "Escribe a quienes no respondieron.",
                "frequency", "WEEKLY", "weekdays", "1", "at", "08:00", "timezone", "America/Guayaquil");
        assertThat(call("POST", "/api/projects/VEN/routines", input, vio).status()).as("solo lectura").isEqualTo(403);
        assertThat(call("POST", "/api/projects/VEN/routines", Map.of("title", "x", "frequency", "WEEKLY", "at", "08:00"), owner).status()).isEqualTo(422);
        JsonNode r = call("POST", "/api/projects/VEN/routines", input, owner).body();
        String id = r.get("id").asString();
        assertThat(r.get("schedule").asString()).isEqualTo("Cada lunes a las 08:00");
        assertThat(r.get("nextRunAt").isNull()).isFalse();

        // Ejecutar ahora: crea la tarjeta con su fecha; una segunda vez no se apila mientras la primera siga abierta.
        JsonNode run = call("POST", "/api/routines/" + id + "/run", null, owner).body();
        String first = run.get("issueKey").asString();
        JsonNode card = call("GET", "/api/issues/" + first, null, owner).body();
        assertThat(card.get("title").asString()).startsWith("Seguimiento de prospectos · ").doesNotContain("{fecha}");
        assertThat(card.get("labels").toString()).contains("rutina");
        assertThat(call("POST", "/api/routines/" + id + "/run", null, owner).body().get("skipped").asString()).contains(first);

        // Se cierra la anterior y le llega su hora: crea la siguiente y pasa a la próxima fecha.
        call("POST", "/api/issues/" + first + "/move", Map.of("status", done, "index", 0), owner);
        jdbc.sql("update routine set next_run_at = now() - interval '1 minute' where id = ?::uuid").param(id).update();
        routines.tick();
        JsonNode after = call("GET", "/api/projects/VEN/routines", null, owner).body().get(0);
        assertThat(after.get("lastIssueKey").asString()).isNotEqualTo(first);
        assertThat(java.time.Instant.parse(after.get("nextRunAt").asString())).isAfter(java.time.Instant.now());

        // Webhook: con su secreto crea la tarjeta con lo que llegó; sin él, 404.
        jdbc.sql("update routine set skip_if_open = false where id = ?::uuid").param(id).update();
        String secret = call("POST", "/api/routines/" + id + "/webhook", null, owner).body().get("secret").asString();
        assertThat(call("POST", "/api/routines/" + id + "/hook", Map.of("text", "Nuevo prospecto: Clínica Norte"), null, "X-Kuador-Secret", "malo").status())
                .isEqualTo(404);
        JsonNode hooked = call("POST", "/api/routines/" + id + "/hook", Map.of("text", "Nuevo prospecto: Clínica Norte"), null, "X-Kuador-Secret", secret).body();
        assertThat(call("GET", "/api/issues/" + hooked.get("issueKey").asString(), null, owner).body().get("description").asString())
                .contains("Clínica Norte");

        assertThat(call("DELETE", "/api/routines/" + id, null, owner).status()).isEqualTo(204);
        assertThat(call("GET", "/api/projects/VEN/routines", null, owner).body().size()).isZero();
    }
}
