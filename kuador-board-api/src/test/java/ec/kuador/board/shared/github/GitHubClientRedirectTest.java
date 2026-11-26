package ec.kuador.board.shared.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import ec.kuador.board.shared.error.DomainException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Un repositorio renombrado en GitHub: la API responde 301 hacia su nueva dirección y el cliente la sigue. */
class GitHubClientRedirectTest {

    private HttpServer server;
    private final List<String> seen = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            seen.add(ex.getRequestMethod() + " " + path + " " + ex.getRequestHeaders().getFirst("Authorization"));
            String location = switch (path) {
                case "/repos/acme/viejo" -> "/repositories/42";
                case "/repos/acme/viejo/pulls" -> "/repositories/42/pulls";
                case "/repos/acme/afuera" -> "https://otro-host.example/repositories/42";
                default -> null;
            };
            if (location != null) {
                ex.getResponseHeaders().add("Location", location);
                ex.sendResponseHeaders(path.endsWith("/pulls") ? 307 : 301, -1);
            } else {
                byte[] body = "{\"full_name\":\"acme/nuevo\"}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GitHubClient client() {
        return new GitHubClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "tok");
    }

    @Test
    void followsARenamedRepositoryWithTheSameRequest() {
        assertThat(client().get("/repos/acme/viejo").path("full_name").asString()).isEqualTo("acme/nuevo");
        client().post("/repos/acme/viejo/pulls", Map.of("title", "x"));
        assertThat(seen).contains("GET /repositories/42 Bearer tok", "POST /repositories/42/pulls Bearer tok");
    }

    @Test
    void neverFollowsARedirectToAnotherHost() {
        assertThatThrownBy(() -> client().get("/repos/acme/afuera")).isInstanceOf(DomainException.class).hasMessageContaining("vuelve a vincularlo");
        assertThat(seen).hasSize(1);
    }
}
