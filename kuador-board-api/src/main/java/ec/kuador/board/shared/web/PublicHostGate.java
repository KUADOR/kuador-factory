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
 * kit y su inicio de sesión OAuth), los runners (emparejarse, avisos y tarjetas), GitHub (webhook) y el chequeo de salud (/livez, /readyz). El resto de la API lo usa solo la web por la red interna,
 * que llega con otro host; desde fuera responde 404, como si no existiera.
 */
public final class PublicHostGate extends OncePerRequestFilter {

    static final List<String> OPEN_PATHS = List.of("/mcp", "/api/agent-kit", "/api/agent-kit.zip", "/api/github/webhook",
            "/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-authorization-server",
            "/oauth/register", "/oauth/token", "/api/telegram/webhook",
            "/api/runner/pair", "/api/runner/pair/token", "/api/runner/setup", "/api/runner/heartbeat", "/api/runner/events", "/api/runner/claim",
            "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/livez", "/readyz");

    private final String publicHost;
    private final WebSecret web;

    public PublicHostGate(String publicUrl, WebSecret web) {
        this.publicHost = URI.create(publicUrl).getHost().toLowerCase(Locale.ROOT);
        this.web = web;
    }

    /**
     * Si la petición puede usar el resto de la API. Con el secreto de la web configurado, solo la web (que lo manda),
     * por cualquier host: así no importa por qué dominio o IP se llegue. Sin él, todo host que no sea el público
     * (comparado sin mayúsculas ni el punto final que aceptan los navegadores).
     */
    boolean fullAccess(HttpServletRequest request) {
        if (web.configured()) return web.fromWeb(request);
        String host = request.getServerName() == null ? "" : request.getServerName().toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return !host.equals(publicHost);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!fullAccess(request) && !OPEN_PATHS.contains(path) && !path.matches("^/api/(telegram|payments)/webhook/[A-Za-z0-9_-]{1,64}$")
                && !path.matches("^/api/runner/leases/[A-Za-z0-9]{1,12}-[0-9]{1,7}/(renew|release)$")) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/problem+json;charset=UTF-8");
            response.getWriter().write("{\"status\":404,\"title\":\"No encontrado\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
