package ec.kuador.board.connections.infrastructure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ec.kuador.board.connections.domain.OAuthClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Contra un servidor MCP y su servidor de autorización mínimos en la propia máquina. */
class HttpOAuthClientTest {

    private HttpServer server;
    private String base;
    private final List<String> forms = new ArrayList<>();
    private boolean challengeNamesMetadata = true;
    private boolean open;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/correo/mcp", x -> {
            if (open) {
                reply(x, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
                return;
            }
            if (challengeNamesMetadata) {
                x.getResponseHeaders().add("WWW-Authenticate", "Bearer resource_metadata=\"" + base + "/meta/recurso\", scope=\"mail.read\"");
            }
            reply(x, 401, "");
        });
        server.createContext("/meta/recurso", x -> reply(x, 200,
                "{\"resource\":\"" + base + "/correo/mcp\",\"authorization_servers\":[\"" + base + "/auth\"],\"scopes_supported\":[\"mail.read\",\"mail.send\"]}"));
        server.createContext("/.well-known/oauth-protected-resource/correo/mcp", x -> reply(x, 200,
                "{\"resource\":\"" + base + "/correo/mcp\",\"authorization_servers\":[\"" + base + "/auth\"],\"scopes_supported\":[\"mail.read\",\"mail.send\"]}"));
        server.createContext("/.well-known/oauth-authorization-server/auth", x -> reply(x, 200, "{\"issuer\":\"" + base + "/auth\",\"authorization_endpoint\":\""
                + base + "/auth/authorize\",\"token_endpoint\":\"" + base + "/auth/token\",\"registration_endpoint\":\"" + base
                + "/auth/register\",\"code_challenge_methods_supported\":[\"S256\"]}"));
        server.createContext("/auth/register", x -> {
            forms.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(x, 201, "{\"client_id\":\"cliente-1\"}");
        });
        server.createContext("/auth/token", x -> {
            String form = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            forms.add(form);
            if (form.contains("code=malo")) reply(x, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"El código venció\"}");
            else reply(x, 200, "{\"access_token\":\"acceso\",\"refresh_token\":\"renovar\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void findsWhereToSignInFromTheChallengeOrTheWellKnownAddress() {
        OAuthClient.Discovery found = new HttpOAuthClient(true).discover(base + "/correo/mcp");

        assertThat(found.authorizationEndpoint()).isEqualTo(base + "/auth/authorize");
        assertThat(found.tokenEndpoint()).isEqualTo(base + "/auth/token");
        assertThat(found.registrationEndpoint()).isEqualTo(base + "/auth/register");
        assertThat(found.resource()).isEqualTo(base + "/correo/mcp");
        assertThat(found.scopes()).as("manda lo que pide el desafío").containsExactly("mail.read");

        challengeNamesMetadata = false;
        assertThat(new HttpOAuthClient(true).discover(base + "/correo/mcp").scopes()).as("sin desafío, la dirección conocida y sus permisos")
                .containsExactly("mail.read", "mail.send");
    }

    @Test
    void registersExchangesAndRefreshes() {
        HttpOAuthClient client = new HttpOAuthClient(true);
        OAuthClient.Discovery found = client.discover(base + "/correo/mcp");

        OAuthClient.Client registered = client.register(found.registrationEndpoint(), "https://go.example/connections/oauth/callback", "Kuador Go");
        assertThat(registered).isEqualTo(new OAuthClient.Client("cliente-1", null));
        assertThat(forms.getLast()).contains("\"redirect_uris\":[\"https://go.example/connections/oauth/callback\"]", "\"token_endpoint_auth_method\":\"none\"");

        OAuthClient.Tokens tokens = client.exchange(found, registered, "codigo", "verificador", "https://go.example/connections/oauth/callback");
        assertThat(tokens).isEqualTo(new OAuthClient.Tokens("acceso", "renovar", 3600));
        assertThat(forms.getLast()).contains("grant_type=authorization_code", "code=codigo", "code_verifier=verificador", "client_id=cliente-1", "resource=http")
                .doesNotContain("client_secret");

        client.refresh(found.tokenEndpoint(), new OAuthClient.Client("app", "secreto"), "renovar", found.resource(), "mail.read offline_access");
        assertThat(forms.getLast()).contains("grant_type=refresh_token", "refresh_token=renovar", "client_secret=secreto", "scope=mail.read+offline_access");

        assertThatThrownBy(() -> client.exchange(found, registered, "malo", "v", "https://go.example/x")).isInstanceOf(OAuthClient.OAuthException.class)
                .hasMessageContaining("El código venció");
    }

    @Test
    void refusesServersThatDoNotAskToSignInOrPointInside() {
        open = true;
        assertThat(new HttpOAuthClient(true).discover(base + "/correo/mcp").tokenEndpoint()).as("hay servidores que saludan sin credencial y la piden después")
                .isEqualTo(base + "/auth/token");
        server.removeContext("/.well-known/oauth-protected-resource/correo/mcp");
        server.removeContext("/meta/recurso");
        assertThatThrownBy(() -> new HttpOAuthClient(true).discover(base + "/correo/mcp")).isInstanceOf(OAuthClient.OAuthException.class)
                .hasMessageContaining("no publica cómo iniciar sesión");
        assertThatThrownBy(() -> new HttpOAuthClient(false).discover(base + "/correo/mcp")).as("sin permiso, nunca hacia la red interna")
                .isInstanceOf(OAuthClient.OAuthException.class).hasMessageContaining("https");
    }

    private static void reply(HttpExchange x, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        if (out.length > 0) x.getResponseBody().write(out);
        x.close();
    }
}
