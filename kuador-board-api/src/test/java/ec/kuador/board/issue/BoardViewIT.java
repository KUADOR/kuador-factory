package ec.kuador.board.issue;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * El tablero no trae el historial: todo el trabajo en curso y solo las últimas terminadas, con el total a la vista. La
 * lista sí llega a todas, con filtros, orden y página resueltos en la base.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class BoardViewIT {

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
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode call(String method, String path, Object body, String token) throws Exception {
        var r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> res = http.send(r.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(method + " " + path + " → " + res.body()).isLessThan(300);
        return res.body().isBlank() ? json.createObjectNode() : json.readTree(res.body());
    }

    private static List<String> keys(JsonNode issues) {
        List<String> out = new ArrayList<>();
        issues.forEach(i -> out.add(i.get("key").asString()));
        return out;
    }

    @Test
    void theBoardShowsTheLatestDoneAndTheListReachesEverything() throws Exception {
        signup.requestCode("lia@tablero.test");
        signup.complete(signup.verifyCode("lia@tablero.test", INBOX.get("lia@tablero.test")),
                new SignupService.Company("Tablero", null, null, null, null, null), "Lía", "clave-larga-de-prueba-1", "TEAM", null);
        String owner = auth.login("lia@tablero.test", "clave-larga-de-prueba-1").token();
        call("POST", "/api/projects", Map.of("key", "TAB", "name", "Tablero", "templateKey", "TASKS"), owner);
        JsonNode project = call("GET", "/api/projects/TAB", null, owner);
        String first = project.get("columns").get(0).get("key").asString();
        String done = project.get("columns").valueStream().filter(c -> c.get("category").asString().equals("DONE")).findFirst().orElseThrow()
                .get("key").asString();

        for (int i = 1; i <= 3; i++) {
            call("POST", "/api/projects/TAB/issues", Map.of("title", "En curso " + i, "type", "TASK", "status", first), owner);
        }
        List<String> finished = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            String key = call("POST", "/api/projects/TAB/issues",
                    Map.of("title", String.format("Terminada %02d", i), "type", "TASK", "status", done, "labels", i % 5 == 0 ? List.of("urgente") : List.of()), owner)
                    .get("key").asString();
            finished.add(key);
        }

        // El tablero: las 3 en curso y las 20 terminadas más recientes; el total dice que hay 25.
        JsonNode board = call("GET", "/api/projects/TAB/board", null, owner);
        List<String> shown = keys(board.get("issues"));
        assertThat(shown).hasSize(23);
        assertThat(board.get("doneVisible").asInt()).isEqualTo(20);
        assertThat(board.get("totals").get(done).asLong()).isEqualTo(25);
        assertThat(shown).as("las 5 primeras en terminarse ya no vienen").doesNotContainAnyElementsOf(finished.subList(0, 5));
        assertThat(shown).as("las últimas sí").containsAll(finished.subList(5, 25));
        // El listado completo de siempre sigue trayendo todo (lo usan otras pantallas).
        assertThat(call("GET", "/api/projects/TAB/issues", null, owner)).hasSize(28);

        // La lista: todas, paginadas.
        JsonNode page = call("GET", "/api/projects/TAB/issues/search?size=10", null, owner);
        assertThat(page.get("total").asLong()).isEqualTo(28);
        assertThat(page.get("items")).hasSize(10);
        assertThat(call("GET", "/api/projects/TAB/issues/search?size=10&page=2", null, owner).get("items")).hasSize(8);

        // Filtros y orden, en la base.
        JsonNode onlyDone = call("GET", "/api/projects/TAB/issues/search?status=" + done + "&sort=title&dir=asc&size=5", null, owner);
        assertThat(onlyDone.get("total").asLong()).isEqualTo(25);
        assertThat(onlyDone.get("items").get(0).get("title").asString()).isEqualTo("Terminada 01");
        assertThat(onlyDone.get("items").get(4).get("title").asString()).isEqualTo("Terminada 05");
        assertThat(call("GET", "/api/projects/TAB/issues/search?sort=title&dir=desc&size=1", null, owner).get("items").get(0).get("title").asString())
                .isEqualTo("Terminada 25");
        assertThat(call("GET", "/api/projects/TAB/issues/search?q=curso", null, owner).get("total").asLong()).isEqualTo(3);
        assertThat(call("GET", "/api/projects/TAB/issues/search?q=" + finished.get(0).toLowerCase(), null, owner).get("total").asLong())
                .as("también busca por clave").isGreaterThanOrEqualTo(1);
        assertThat(call("GET", "/api/projects/TAB/issues/search?label=urgente", null, owner).get("total").asLong()).isEqualTo(5);
        assertThat(call("GET", "/api/projects/TAB/issues/search?q=100%25", null, owner).get("total").asLong()).as("el % se busca tal cual").isZero();
        assertThat(call("GET", "/api/projects/TAB/issues/search?size=1000", null, owner).get("size").asInt()).as("la página tiene tope").isEqualTo(100);
    }

    /** La salud dice qué réplica respondió: así se cuentan desde fuera. Solo ahí. */
    @Test
    void healthAnswersWithTheReplicaThatServedIt() throws Exception {
        var ready = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/readyz")).build(), HttpResponse.BodyHandlers.ofString());
        var again = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/readyz")).build(), HttpResponse.BodyHandlers.ofString());
        var other = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/public/packages")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(ready.headers().firstValue("X-Kuador-Replica")).isPresent().get().asString().hasSize(8);
        assertThat(again.headers().firstValue("X-Kuador-Replica")).as("la misma réplica, el mismo identificador").isEqualTo(ready.headers().firstValue("X-Kuador-Replica"));
        assertThat(other.headers().firstValue("X-Kuador-Replica")).isEmpty();
    }
}
