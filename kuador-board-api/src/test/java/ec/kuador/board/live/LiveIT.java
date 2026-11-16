package ec.kuador.board.live;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
import tools.jackson.databind.ObjectMapper;

/** Un navegador abierto en un proyecto recibe el aviso cuando otro mueve o comenta un issue; otro espacio, no. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=", "board.agents.anthropic-api-key=",
        "board.sync.allowed-roots=", "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class LiveIT {

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

    private String owner(String email, String company) {
        signup.requestCode(email);
        String t = signup.verifyCode(email, INBOX.get(email));
        signup.complete(t, new SignupService.Company(company, null, null, null, null, null), "Persona", "clave-larga-de-prueba-1", "FACTORY");
        return auth.login(email, "clave-larga-de-prueba-1").token();
    }

    private int call(String method, String path, Object body, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(req.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private String create(String project, String title, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/projects/" + project + "/issues"))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("title", title, "type", "STORY", "status", "BACKLOG"))));
        return json.readTree(http.send(req.build(), HttpResponse.BodyHandlers.ofString()).body()).get("key").asText();
    }

    /** Abre el stream y deja cada evento («nombre datos») en una cola. */
    private BlockingQueue<String> listen(String query, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/live" + query))
                .header("Accept", "text/event-stream").header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        Thread.ofVirtual().start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
                String line, name = null;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) name = line.substring(6).strip();
                    else if (line.startsWith("data:")) events.add(name + " " + line.substring(5).strip());
                }
            } catch (Exception ignored) {
                // la prueba terminó
            }
        });
        assertThat(events.poll(5, TimeUnit.SECONDS)).as("primer mensaje").startsWith("listo");
        return events;
    }

    private List<String> drain(BlockingQueue<String> q, Duration wait) throws InterruptedException {
        List<String> out = new ArrayList<>();
        String e = q.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
        while (e != null) {
            out.add(e);
            e = q.poll(300, TimeUnit.MILLISECONDS);
        }
        return out;
    }

    @Test
    void openBoardsHearAboutChangesOnlyFromTheirWorkspaceAndProject() throws Exception {
        String ana = owner("ana@live.test", "Live Uno");
        String beto = owner("beto@live.test", "Live Dos");
        assertThat(call("POST", "/api/projects", Map.of("key", "LIV", "name", "Live", "templateKey", "SOFTWARE_FACTORY"), ana)).isEqualTo(201);
        assertThat(call("POST", "/api/projects", Map.of("key", "OTR", "name", "Otro", "templateKey", "SOFTWARE_FACTORY"), ana)).isEqualTo(201);
        assertThat(call("POST", "/api/projects", Map.of("key", "LIV", "name", "Live de Beto", "templateKey", "SOFTWARE_FACTORY"), beto)).isEqualTo(201);

        BlockingQueue<String> board = listen("?project=liv", ana);
        BlockingQueue<String> otherWorkspace = listen("", beto);

        String key = create("LIV", "Esqueleto", ana);
        assertThat(call("POST", "/api/issues/" + key + "/move", Map.of("status", "REFINEMENT", "index", 0), ana)).isEqualTo(200);
        assertThat(call("POST", "/api/issues/" + key + "/comments", Map.of("body", "Listo para refinar"), ana)).isEqualTo(201);
        create("OTR", "De otro proyecto", ana);
        // Asignárselo al Agente PO: la oficina se entera de que el agente empezó y terminó (aquí, sin clave de IA, lo omite).
        var members = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/members")).header("Authorization", "Bearer " + ana).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        String po = json.readTree(members).valueStream().filter(m -> "PRODUCT_OWNER".equals(m.path("role").asText())).findFirst().orElseThrow().get("id").asText();
        assertThat(call("PUT", "/api/issues/" + key + "/assignee", Map.of("assigneeId", po), ana)).isEqualTo(200);

        List<String> heard = drain(board, Duration.ofSeconds(5));
        assertThat(heard).allMatch(e -> e.startsWith("cambio ") && e.contains("\"proyecto\":\"LIV\"") && e.contains("\"issue\":\"" + key + "\""));
        assertThat(heard).anyMatch(e -> e.contains("\"tipo\":\"CREADO\""))
                .anyMatch(e -> e.contains("\"tipo\":\"MOVIDO\""))
                .anyMatch(e -> e.contains("\"tipo\":\"COMENTADO\""))
                .anyMatch(e -> e.contains("\"tipo\":\"AGENTE_RUNNING\""))
                .anyMatch(e -> e.contains("\"tipo\":\"AGENTE_SKIPPED\""));
        assertThat(drain(otherWorkspace, Duration.ofSeconds(1))).as("otro espacio no se entera").isEmpty();
        assertThat(call("GET", "/api/live", null, "token-falso")).isEqualTo(401);
    }
}
