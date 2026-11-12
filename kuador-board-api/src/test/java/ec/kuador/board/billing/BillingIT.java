package ec.kuador.board.billing;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Planes a la venta, límites del plan gratuito, cambio de plan con prueba y la administración de la plataforma. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=", "board.agents.anthropic-api-key=",
        "board.sync.allowed-roots=", "board.platform.admins=Pia@Plataforma.test",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class BillingIT {

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
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    record Reply(int status, JsonNode body) {}

    private Reply call(String method, String path, Object body, String token) throws Exception {
        var r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) r.header("Authorization", "Bearer " + token);
        HttpResponse<String> res = http.send(r.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(), res.body().isBlank() ? null : json.readTree(res.body()));
    }

    private String newWorkspace(String email, String company, String plan) {
        signup.requestCode(email);
        signup.complete(signup.verifyCode(email, INBOX.get(email)), new SignupService.Company(company, null, null, null, null, null),
                "Persona", "clave-larga-de-prueba-1", plan);
        return auth.login(email, "clave-larga-de-prueba-1").token();
    }

    private Reply project(String token, String key, String template) throws Exception {
        return call("POST", "/api/projects", Map.of("key", key, "name", "Tablero " + key, "templateKey", template), token);
    }

    @Test
    void theFreePlanLimitsBoardsAndPeopleAndLeavesOutTheFactory() throws Exception {
        Reply plans = call("GET", "/api/public/plans", null, null);
        assertThat(plans.status()).as("los planes se ven sin sesión").isEqualTo(200);
        assertThat(plans.body().findValuesAsString("code")).containsExactly("FREE", "TEAM", "FACTORY");

        String token = newWorkspace("fer@libre.test", "Libre", null);
        Reply mine = call("GET", "/api/workspace/plan", null, token);
        assertThat(mine.body().get("plan").get("code").asString()).isEqualTo("FREE");
        assertThat(mine.body().get("status").asString()).isEqualTo("ACTIVE");

        assertThat(project(token, "FAB", "SOFTWARE_FACTORY").body().get("detail").asString()).contains("fábrica de software");
        assertThat(project(token, "UNO", "TASKS").status()).isEqualTo(201);
        assertThat(project(token, "DOS", "SALES").status()).isEqualTo(201);
        Reply third = project(token, "TRE", "TASKS");
        assertThat(third.status()).isEqualTo(422);
        assertThat(third.body().get("detail").asString()).contains("hasta 2 tablero(s)");

        // La dueña cuenta: con el plan gratuito caben dos personas más.
        Map<String, Object> person = new LinkedHashMap<>(Map.of("kind", "HUMAN", "role", "VENTAS", "avatarColor", "#8fb4ff"));
        for (String name : List.of("Ana", "Beto")) {
            person.put("name", name);
            assertThat(call("POST", "/api/members", person, token).status()).isEqualTo(201);
        }
        person.put("name", "Carla");
        assertThat(call("POST", "/api/members", person, token).body().get("detail").asString()).contains("hasta 3 persona(s)");

        // Subir a Factory usa su prueba de 14 días y habilita todo.
        Reply upgraded = call("PUT", "/api/workspace/plan", Map.of("plan", "FACTORY"), token);
        assertThat(upgraded.body().get("status").asString()).isEqualTo("TRIAL");
        assertThat(upgraded.body().get("effective").get("code").asString()).isEqualTo("FACTORY");
        assertThat(project(token, "FAB", "SOFTWARE_FACTORY").status()).isEqualTo(201);

        // La prueba se usa una sola vez: volver a un plan de pago queda pendiente de pago y rige el gratuito.
        call("PUT", "/api/workspace/plan", Map.of("plan", "FREE"), token);
        Reply again = call("PUT", "/api/workspace/plan", Map.of("plan", "TEAM"), token);
        assertThat(again.body().get("status").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(again.body().get("effective").get("code").asString()).isEqualTo("FREE");
    }

    @Test
    void onlyPlatformAdminsEditPlansAndLicenses() throws Exception {
        String someone = newWorkspace("leo@cliente.test", "Cliente", "TEAM");
        assertThat(call("GET", "/api/platform/plans", null, someone).status()).isEqualTo(403);
        assertThat(call("GET", "/api/auth/me", null, someone).body().get("platformAdmin").asBoolean()).isFalse();

        String admin = newWorkspace("pia@plataforma.test", "Plataforma", null);
        assertThat(call("GET", "/api/auth/me", null, admin).body().get("platformAdmin").asBoolean()).isTrue();

        Map<String, Object> team = new LinkedHashMap<>();
        team.put("name", "Equipo");
        team.put("tagline", "Para equipos \"grandes\".");
        team.put("priceMonthly", 35.5);
        team.put("currency", "usd");
        team.put("features", List.of("Hasta 20 personas", "Agentes, con \"comillas\""));
        team.put("maxMembers", 20);
        team.put("maxProjects", null);
        team.put("factory", false);
        team.put("trialDays", 7);
        team.put("highlighted", true);
        team.put("sortOrder", 20);
        team.put("active", true);
        Reply saved = call("PUT", "/api/platform/plans/TEAM", team, admin);
        assertThat(saved.status()).isEqualTo(200);
        JsonNode published = call("GET", "/api/public/plans", null, null).body().get(1);
        assertThat(published.get("priceMonthly").asDouble()).isEqualTo(35.5);
        assertThat(published.get("currency").asString()).isEqualTo("USD");
        assertThat(published.get("features").get(1).asString()).isEqualTo("Agentes, con \"comillas\"");
        assertThat(published.get("maxProjects").isNull()).isTrue();

        // Registrar un pago manual: la licencia queda activa.
        JsonNode client = null;
        for (JsonNode w : call("GET", "/api/platform/workspaces", null, admin).body()) {
            if (w.get("slug").asString().equals("cliente")) client = w;
        }
        assertThat(client.get("status").asString()).isEqualTo("TRIAL");
        Reply paid = call("PUT", "/api/platform/workspaces/" + client.get("workspaceId").asString() + "/license",
                Map.of("plan", "TEAM", "status", "ACTIVE", "paidUntil", "2099-01-01T00:00:00Z"), admin);
        assertThat(paid.body().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(call("GET", "/api/workspace/plan", null, someone).body().get("paidUntil").asString()).startsWith("2099-01-01");
    }
}
