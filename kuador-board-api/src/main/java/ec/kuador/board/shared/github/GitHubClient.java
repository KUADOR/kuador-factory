package ec.kuador.board.shared.github;

import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente mínimo de la API REST de GitHub. La URL base la fija la configuración del servidor (no
 * el usuario), así que no se puede usar para llegar a otros hosts. El token nunca se registra.
 */
public final class GitHubClient {

    public static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http;
    private final String baseUrl;
    private final String token;

    public GitHubClient(HttpClient http, String baseUrl, String token) {
        this.http = http;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
    }

    public JsonNode get(String path) {
        return send("GET", path, null).orElseThrow(() -> new DomainException("GitHub no encontró " + path + "."));
    }

    public Optional<JsonNode> find(String path) {
        return send("GET", path, null);
    }

    public JsonNode post(String path, Object body) {
        return send("POST", path, body).orElseThrow(() -> new DomainException("GitHub no encontró " + path + "."));
    }

    /** POST que devuelve vacío si GitHub responde 404 (para decidir qué decir según el caso). */
    public Optional<JsonNode> tryPost(String path, Object body) {
        return send("POST", path, body);
    }

    public JsonNode patch(String path, Object body) {
        return send("PATCH", path, body).orElseThrow(() -> new DomainException("GitHub no encontró " + path + "."));
    }

    public JsonNode put(String path, Object body) {
        return send("PUT", path, body).orElseThrow(() -> new DomainException("GitHub no encontró " + path + "."));
    }

    /** Vacío si GitHub responde 404. */
    private Optional<JsonNode> send(String method, String path, Object body) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "kuador-board");
        // Sin token: llamadas públicas (p. ej. convertir el manifiesto de una GitHub App).
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body == null) {
            req.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            req.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new DomainException("No se pudo conectar con GitHub: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException("Se interrumpió la conexión con GitHub.");
        }
        int status = res.statusCode();
        if (status == 404) return Optional.empty();
        if (status >= 200 && status < 300) {
            return Optional.of(res.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(res.body()));
        }
        String message = githubMessage(res.body());
        throw switch (status) {
            case 401 -> new DomainException("GitHub rechazó la credencial (vencida o inválida). Vuelve a vincular el repositorio o la conexión.");
            case 403 -> new Forbidden("GitHub negó el acceso: " + message
                    + ". Revisa que la credencial tenga permisos de Contents y Pull requests (lectura y escritura) sobre este repo.", message);
            case 409, 422 -> new ConflictException("GitHub rechazó la operación: " + message + ".");
            default -> new DomainException("GitHub respondió " + status + ": " + message + ".");
        };
    }

    /** GitHub respondió 403. {@code githubMessage} es su texto tal cual (p. ej. el aviso de plan gratuito). */
    public static final class Forbidden extends DomainException {
        private final String githubMessage;

        Forbidden(String message, String githubMessage) {
            super(message);
            this.githubMessage = githubMessage;
        }

        public String githubMessage() {
            return githubMessage;
        }
    }

    private static String githubMessage(String body) {
        try {
            String m = JSON.readTree(body).path("message").asString("");
            return m.isBlank() ? "sin detalle" : m;
        } catch (RuntimeException e) {
            return "sin detalle";
        }
    }
}
