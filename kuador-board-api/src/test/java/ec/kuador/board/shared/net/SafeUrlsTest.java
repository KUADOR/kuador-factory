package ec.kuador.board.shared.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.shared.error.DomainException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class SafeUrlsTest {

    @Test
    void onlyPublicHttpsIsAllowed() {
        for (String url : new String[] {"http://example.com/mcp", "https://127.0.0.1/mcp", "https://localhost/mcp", "https://10.0.0.5/x",
                "https://192.168.1.10/x", "https://169.254.169.254/latest/meta-data", "https://[::1]/x", "ftp://example.com", "https://user:pass@example.com/x",
                "no es una url", ""}) {
            assertThatThrownBy(() -> SafeUrls.requirePublicHttps(url, false)).as(url).isInstanceOf(DomainException.class);
        }
    }

    @Test
    void internalRangesAreRecognized() throws Exception {
        assertThat(SafeUrls.internal(InetAddress.getByName("100.64.1.1"))).isTrue();
        assertThat(SafeUrls.internal(InetAddress.getByName("172.16.5.5"))).isTrue();
        assertThat(SafeUrls.internal(InetAddress.getByName("fd00::1"))).isTrue();
        assertThat(SafeUrls.internal(InetAddress.getByName("8.8.8.8"))).isFalse();
    }

    @Test
    void localDevelopmentCanOptIn() {
        assertThat(SafeUrls.requirePublicHttps("http://127.0.0.1:9000/mcp", true).getPort()).isEqualTo(9000);
    }
}
