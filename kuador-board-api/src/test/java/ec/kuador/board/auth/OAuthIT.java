package ec.kuador.board.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
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

/**
 * Inicio de sesión de un cliente de IA en el servidor MCP, como lo hace Claude Code: descubre el servidor de
 * autorización desde el 401, se registra solo, la persona aprueba en la web y el canje con PKCE le da su conexión.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "board.public-url=https://api.tablero.test", "board.web-url=https://tablero.test",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class OAuthIT {

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

    record Reply(int status, JsonNode body, HttpResponse<String> raw) {}

    private Reply send(HttpRequest.Builder req) throws Exception {
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        boolean isJson = res.headers().firstValue("Content-Type").orElse("").contains("json") && !res.body().isBlank();
        return new Reply(res.statusCode(), isJson ? json.readTree(res.body()) : null, res);
    }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private Reply json(String method, String path, Object body, String token) throws Exception {
        var r = req(path).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) r.header("Authorization", "Bearer " + token);
        return send(r);
    }

    private Reply form(String path, Map<String, String> fields) throws Exception {
        String body = fields.entrySet().stream().map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return send(req(path).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private static String s256(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private static Map<String, String> query(String url) {
        return java.util.Arrays.stream(URI.create(url).getRawQuery().split("&")).map(p -> p.split("=", 2))
                .collect(Collectors.toMap(p -> p[0], p -> URLDecoder.decode(p[1])));
    }

    private static final class URLDecoder {
        static String decode(String v) {
            return java.net.URLDecoder.decode(v, StandardCharsets.UTF_8);
        }
    }

    @Test
    void anAiClientLogsInWithPkceAndGetsItsPersonsConnection() throws Exception {
        signup.requestCode("ole@oa.test");
        signup.complete(signup.verifyCode("ole@oa.test", INBOX.get("ole@oa.test")),
                new SignupService.Company("Oa", null, null, null, null, null), "Ole", "clave-de-ole-123", "FACTORY");
        String session = auth.login("ole@oa.test", "clave-de-ole-123").token();

        // 1. Sin token, /mcp responde 401 y dice dónde están los metadatos del recurso.
        Reply unauthorized = json("POST", "/mcp", Map.of("jsonrpc", "2.0", "id", 1, "method", "ping"), null);
        assertThat(unauthorized.status()).isEqualTo(401);
        assertThat(unauthorized.raw().headers().firstValue("WWW-Authenticate").orElse(""))
                .isEqualTo("Bearer resource_metadata=\"https://api.tablero.test/.well-known/oauth-protected-resource/mcp\"");
        JsonNode resource = json("GET", "/.well-known/oauth-protected-resource/mcp", null, null).body();
        assertThat(resource.get("resource").asString()).isEqualTo("https://api.tablero.test/mcp");
        JsonNode server = json("GET", "/.well-known/oauth-authorization-server", null, null).body();
        assertThat(server.get("issuer").asString()).isEqualTo("https://api.tablero.test");
        assertThat(server.get("authorization_endpoint").asString()).as("la persona aprueba en la web").isEqualTo("https://tablero.test/oauth/authorize");
        assertThat(server.get("code_challenge_methods_supported").get(0).asString()).isEqualTo("S256");

        // 2. Registro dinámico: solo redirects seguros.
        assertThat(json("POST", "/oauth/register", Map.of("client_name", "Malo", "redirect_uris", List.of("http://malo.test/cb")), null).status()).isEqualTo(400);
        Reply registered = json("POST", "/oauth/register", Map.of("client_name", "Claude Code (oa)", "redirect_uris", List.of("http://localhost:4567/callback")), null);
        assertThat(registered.status()).isEqualTo(201);
        String clientId = registered.body().get("client_id").asString();

        // 3. La web muestra quién pide y aprueba con la sesión de la persona. En loopback el puerto puede cambiar.
        String redirect = "http://localhost:5050/callback";
        JsonNode info = json("GET", "/api/oauth/client?client_id=" + clientId + "&redirect_uri=" + URLEncoder.encode(redirect, StandardCharsets.UTF_8), null, session).body();
        assertThat(info.get("name").asString()).isEqualTo("Claude Code (oa)");
        assertThat(info.get("local").asBoolean()).isTrue();
        assertThat(json("GET", "/api/oauth/client?client_id=" + clientId + "&redirect_uri=" + URLEncoder.encode("https://otro.test/cb", StandardCharsets.UTF_8), null, session)
                .status()).as("otro destino no").isEqualTo(400);
        String verifier = "v".repeat(20) + "-verificador-de-prueba-1234567890";
        Reply denied = json("POST", "/api/oauth/authorize", Map.of("clientId", clientId, "redirectUri", redirect, "codeChallenge", s256(verifier),
                "codeChallengeMethod", "S256", "state", "xyz", "approved", false), session);
        assertThat(query(denied.body().get("redirect").asString())).containsEntry("error", "access_denied").containsEntry("state", "xyz");
        Reply approved = json("POST", "/api/oauth/authorize", Map.of("clientId", clientId, "redirectUri", redirect, "codeChallenge", s256(verifier),
                "codeChallengeMethod", "S256", "state", "xyz", "approved", true), session);
        assertThat(approved.status()).isEqualTo(200);
        Map<String, String> back = query(approved.body().get("redirect").asString());
        assertThat(back).containsEntry("state", "xyz").containsKey("code");
        String code = back.get("code");

        // 4. Canje: el verificador equivocado no sirve; el correcto da la conexión, una sola vez.
        Map<String, String> exchange = Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", redirect, "client_id", clientId,
                "code_verifier", "otro-verificador-que-no-corresponde-000000000");
        Reply wrong = form("/oauth/token", exchange);
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.body().get("error").asString()).isEqualTo("invalid_grant");
        Map<String, String> good = new java.util.HashMap<>(exchange);
        good.put("code_verifier", verifier);
        Reply token = form("/oauth/token", good);
        assertThat(token.status()).as(String.valueOf(token.body())).isEqualTo(200);
        String access = token.body().get("access_token").asString();
        assertThat(access).startsWith("kfr_");
        assertThat(token.body().get("token_type").asString()).isEqualTo("Bearer");
        assertThat(form("/oauth/token", good).status()).as("el código vale una vez").isEqualTo(400);

        // 5. Con ese token, el MCP responde como esa persona; la conexión aparece en su lista y se puede revocar.
        Reply init = json("POST", "/mcp", Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of("protocolVersion", "2025-06-18")), access);
        assertThat(init.status()).isEqualTo(200);
        assertThat(init.body().get("result").get("serverInfo").get("name").asString()).isEqualTo("oa");
        JsonNode connections = json("GET", "/api/auth/runner-tokens", null, session).body();
        assertThat(connections).anySatisfy(c -> assertThat(c.get("name").asString()).contains("Claude Code (oa)"));
        assertThat(json("GET", "/api/projects", null, access).status()).as("solo abre el MCP").isEqualTo(401);
    }
}
