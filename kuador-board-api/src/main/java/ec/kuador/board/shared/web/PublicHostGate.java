package ec.kuador.board.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cuando la API tiene dominio propio en Internet, por ese dominio solo entran los clientes de IA (MCP, su
 * kit y su inicio de sesión OAuth), GitHub (webhook) y el chequeo de salud. El resto de la API lo usa solo la web por la red interna,
 * que llega con otro host; desde fuera responde 404, como si no existiera.
 */
public final class PublicHostGate extends OncePerRequestFilter {

    static final List<String> OPEN_PATHS = List.of("/mcp", "/api/agent-kit", "/api/agent-kit.zip", "/api/github/webhook",
            "/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-authorization-server",
            "/oauth/register", "/oauth/token", "/api/telegram/webhook",
            "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness");

    private final String publicHost;

    public PublicHostGate(String publicUrl) {
        this.publicHost = URI.create(publicUrl).getHost().toLowerCase(Locale.ROOT);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getServerName() == null ? "" : request.getServerName().toLowerCase(Locale.ROOT);
        String path = request.getRequestURI();
        if (host.equals(publicHost) && !OPEN_PATHS.contains(path) && !path.matches("^/api/(telegram|payments)/webhook/[A-Za-z0-9_-]{1,64}$")) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/problem+json;charset=UTF-8");
            response.getWriter().write("{\"status\":404,\"title\":\"No encontrado\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
