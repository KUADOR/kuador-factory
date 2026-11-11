package ec.kuador.board.github.infrastructure.http;

import ec.kuador.board.github.domain.GitHubAppGateway;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.github.GitHubClient;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** GitHub como app: API REST (JWT o token de instalación) y el canje OAuth en github.com. */
@Component
class HttpGitHubAppGateway implements GitHubAppGateway {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String apiUrl;
    private final String webUrl;
    private final Clock clock;

    HttpGitHubAppGateway(@Value("${board.github.api-url:https://api.github.com}") String apiUrl,
            @Value("${board.github.web-url:https://github.com}") String webUrl, Clock clock) {
        this.apiUrl = apiUrl;
        this.webUrl = webUrl.replaceAll("/+$", "");
        this.clock = clock;
    }

    @Override
    public ConvertedApp convertManifest(String code) {
        JsonNode app = new GitHubClient(http, apiUrl, null)
                .post("/app-manifests/" + URLEncoder.encode(code, StandardCharsets.UTF_8) + "/conversions", GitHubClient.JSON.createObjectNode());
        return new ConvertedApp(app.path("id").asLong(), app.path("slug").asString(), app.path("name").asString(),
                app.path("owner").path("login").asString(), app.path("html_url").asString(), app.path("client_id").asString(),
                app.path("client_secret").asString(), app.path("pem").asString(), app.path("webhook_secret").asString(null));
    }

    @Override
    public Account installationAccount(AppKeys app, long installationId) {
        JsonNode inst = asApp(app).get("/app/installations/" + installationId);
        return new Account(inst.path("account").path("login").asString(), inst.path("account").path("type").asString("Organization"));
    }

    @Override
    public Token installationToken(AppKeys app, long installationId) {
        JsonNode t = asApp(app).post("/app/installations/" + installationId + "/access_tokens", GitHubClient.JSON.createObjectNode());
        return new Token(t.path("token").asString(), Instant.parse(t.path("expires_at").asString()));
    }

    @Override
    public Set<Long> userInstallations(AppKeys app, String oauthCode) {
        String userToken = exchange(app, oauthCode);
        Set<Long> ids = new HashSet<>();
        for (JsonNode i : new GitHubClient(http, apiUrl, userToken).get("/user/installations?per_page=100").path("installations")) {
            ids.add(i.path("id").asLong());
        }
        return ids;
    }

    private GitHubClient asApp(AppKeys app) {
        return new GitHubClient(http, apiUrl, AppJwt.sign(app.appId(), app.privateKeyPem(), clock.instant()));
    }

    /** Canje OAuth: devuelve un token de usuario que solo se usa en esta petición. */
    private String exchange(AppKeys app, String code) {
        String form = "client_id=" + enc(app.clientId()) + "&client_secret=" + enc(app.clientSecret()) + "&code=" + enc(code);
        HttpRequest req = HttpRequest.newBuilder(URI.create(webUrl + "/login/oauth/access_token"))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode body = GitHubClient.JSON.readTree(res.body());
            String token = body.path("access_token").asString("");
            if (res.statusCode() != 200 || token.isBlank()) {
                throw new DomainException("GitHub no confirmó quién instaló la app (" + body.path("error_description").asString("sin detalle")
                        + "). Vuelve a intentarlo desde el tablero.");
            }
            return token;
        } catch (IOException e) {
            throw new DomainException("No se pudo conectar con GitHub: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException("Se interrumpió la conexión con GitHub.");
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
