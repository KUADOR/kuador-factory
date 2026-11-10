package ec.kuador.board.auth.infrastructure.security;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.shared.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lee {@code Authorization: Bearer <token>}: si es válido, autentica la petición y fija su espacio
 * de trabajo para todo lo que sigue. El espacio se limpia siempre al terminar (los hilos se
 * reutilizan entre peticiones).
 */
class BearerTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final AuthService auth;

    BearerTokenFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
                auth.authenticate(header.substring(PREFIX.length())).ifPresent(who -> {
                    var kind = new SimpleGrantedAuthority(who.agent() ? "ROLE_AGENT" : "ROLE_HUMAN");
                    // ROLE_EDITOR: puede escribir en el tablero. Quien solo mira no lo recibe.
                    var authorities = who.viewOnly() ? List.of(kind) : List.of(kind, new SimpleGrantedAuthority("ROLE_EDITOR"));
                    SecurityContextHolder.getContext().setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(who, null, authorities));
                    TenantContext.set(who.workspaceId());
                });
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
