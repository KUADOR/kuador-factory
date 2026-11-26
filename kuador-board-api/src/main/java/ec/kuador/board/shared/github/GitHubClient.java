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

    /** Cuántas redirecciones seguidas se aceptan antes de rendirse. */
    private static final int MAX_REDIRECTS = 3;

    /**
     * Vacío si GitHub responde 404. Un repositorio renombrado o transferido responde 301 (o 307 en una escritura) hacia
     * su nueva dirección: se repite la misma petición ahí, solo dentro del mismo host de la API (el token no sale de ahí).
     */
    private Optional<JsonNode> send(String method, String path, Object body) {
        URI target = URI.create(baseUrl + path);
        HttpResponse<String> res = exchange(method, target, body);
        for (int hops = 0; isRedirect(res.statusCode()) && hops < MAX_REDIRECTS; hops++) {
            URI next = res.headers().firstValue("Location").map(target::resolve).orElse(null);
            if (next == null || !sameHost(next)) break;
            target = next;
            res = exchange(method, target, body);
        }
        return answer(res);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 307 || status == 308;
    }

    private boolean sameHost(URI uri) {
        URI base = URI.create(baseUrl);
        return java.util.Objects.equals(uri.getScheme(), base.getScheme()) && java.util.Objects.equals(uri.getHost(), base.getHost())
                && uri.getPort() == base.getPort();
    }

    private HttpResponse<String> exchange(String method, URI uri, Object body) {
        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
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
        try {
            return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new DomainException("No se pudo conectar con GitHub: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException("Se interrumpió la conexión con GitHub.");
        }
    }

    private Optional<JsonNode> answer(HttpResponse<String> res) {
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
            case 301, 302, 307, 308 -> new DomainException("GitHub dice que el repositorio cambió de lugar y no se pudo seguir: vuelve a vincularlo con su nombre nuevo.");
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
