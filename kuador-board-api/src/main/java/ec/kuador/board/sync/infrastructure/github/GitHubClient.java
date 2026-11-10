package ec.kuador.board.sync.infrastructure.github;

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
final class GitHubClient {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http;
    private final String baseUrl;
    private final String token;

    GitHubClient(HttpClient http, String baseUrl, String token) {
        this.http = http;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
    }

    JsonNode get(String path) {
        return send("GET", path, null).orElseThrow(() -> new DomainException("GitHub no encontró " + path + "."));
    }

    Optional<JsonNode> find(String path) {
        return send("GET", path, null);
    }

    JsonNode post(String path, Object body) {
        return send("POST", path, body).orElseThrow(() -> new DomainException("GitHub no encontró " + path + "."));
    }

    JsonNode patch(String path, Object body) {
        return send("PATCH", path, body).orElseThrow(() -> new DomainException("GitHub no encontró " + path + "."));
    }

    /** Vacío si GitHub responde 404. */
    private Optional<JsonNode> send(String method, String path, Object body) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "kuador-board")
                .header("Authorization", "Bearer " + token);
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
            case 401 -> new DomainException("GitHub rechazó el token (vencido o inválido). Vuelve a vincular el repositorio.");
            case 403 -> new DomainException("GitHub negó el acceso: " + message
                    + ". Revisa que el token tenga permisos de Contents y Pull requests (lectura y escritura) sobre este repo.");
            case 409, 422 -> new ConflictException("GitHub rechazó la operación: " + message + ".");
            default -> new DomainException("GitHub respondió " + status + ": " + message + ".");
        };
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
