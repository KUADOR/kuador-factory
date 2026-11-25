package ec.kuador.board.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Qué réplica respondió, en las consultas de salud ({@code /readyz}, {@code /livez}): un identificador al azar que
 * nace con el proceso. Sirve para comprobar desde fuera cuántas réplicas atienden y que un despliegue las cambió. No
 * dice nada del servidor ni de la versión.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class ReplicaHeader extends OncePerRequestFilter {

    static final String HEADER = "X-Kuador-Replica";

    private final String id = UUID.randomUUID().toString().substring(0, 8);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.equals("/readyz") && !path.equals("/livez");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader(HEADER, id);
        chain.doFilter(request, response);
    }
}
