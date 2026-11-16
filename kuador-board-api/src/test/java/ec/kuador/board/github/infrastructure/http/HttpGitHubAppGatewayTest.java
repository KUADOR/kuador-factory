package ec.kuador.board.github.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import ec.kuador.board.github.domain.GitHubAppGateway;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Una instalación que GitHub ya no conoce (app desinstalada o vuelta a crear) se reconoce, en vez de un 404 genérico. */
class HttpGitHubAppGatewayTest {

    @Test
    void anInstallationGitHubNoLongerKnowsIsReportedAsGone() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = "{\"message\":\"Not Found\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(gen.generateKeyPair().getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            HttpGitHubAppGateway gateway = new HttpGitHubAppGateway("http://127.0.0.1:" + server.getAddress().getPort(), "https://github.com",
                    Clock.systemUTC());

            assertThatThrownBy(() -> gateway.installationToken(new GitHubAppGateway.AppKeys(1, "cliente", "secreto", pem), 180907191L))
                    .isInstanceOf(GitHubAppGateway.InstallationGone.class);
        } finally {
            server.stop(0);
        }
    }
}
