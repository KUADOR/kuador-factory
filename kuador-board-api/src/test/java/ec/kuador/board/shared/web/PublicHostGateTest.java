package ec.kuador.board.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicHostGateTest {

    private final PublicHostGate gate = new PublicHostGate("https://go-api.example.com", new WebSecret(""));

    @ParameterizedTest
    @CsvSource({
            "go-api.example.com, /mcp, 200",
            "GO-API.example.com, /api/agent-kit.zip, 200",
            "go-api.example.com, /api/github/webhook, 200",
            "go-api.example.com, /actuator/health, 200",
            "go-api.example.com, /api/auth/login, 404",
            "go-api.example.com, /api/projects, 404",
            "go-api.example.com, /mcp/../api/projects, 404",
            "go-api.example.com, /, 404",
            "go-api.example.com., /api/auth/login, 404",
            "board-api, /api/auth/login, 200",
            "board-api, /api/projects, 200"
    })
    void soloRutasDeAgentesPorElDominioPublico(String host, String path, int status) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServerName(host);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        gate.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(chain.getRequest() != null).isEqualTo(status == 200);
    }

    /** Con el secreto de la web, el resto de la API solo se abre a quien lo trae, llegue por el host que llegue. */
    @ParameterizedTest
    @CsvSource({
            "board-api, /api/projects, , 404",
            "203.0.113.5, /api/auth/login, , 404",
            "board-api, /api/projects, secreto-de-la-web, 200",
            "go-api.example.com, /api/projects, otro, 404",
            "go-api.example.com, /mcp, , 200"
    })
    void conSecretoSoloLaWeb(String host, String path, String sent, int status) throws Exception {
        PublicHostGate withSecret = new PublicHostGate("https://go-api.example.com", new WebSecret("secreto-de-la-web"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServerName(host);
        if (sent != null) request.addHeader(WebSecret.HEADER, sent);
        MockHttpServletResponse response = new MockHttpServletResponse();
        withSecret.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(status);
    }
}
