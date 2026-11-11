package ec.kuador.board.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicHostGateTest {

    private final PublicHostGate gate = new PublicHostGate("https://go-api.example.com");

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
}
