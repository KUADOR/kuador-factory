package ec.kuador.board.auth.infrastructure.security;

import ec.kuador.board.shared.web.AuditHints;
import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.auth.application.PlatformAdmins;
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
    private final PlatformAdmins platform;

    BearerTokenFilter(AuthService auth, PlatformAdmins platform) {
        this.auth = auth;
        this.platform = platform;
    }

    /** Rutas que acepta la conexión de agentes locales de una persona. */
    static boolean runnerPath(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/mcp") || path.equals("/api/agent-kit") || path.equals("/api/agent-kit.zip")
                // El runner: instalación, señal de vida, avisos, pedir tarjetas y sus préstamos (no administra sus máquinas).
                || (path.startsWith("/api/runner/") && !path.startsWith("/api/runner/machines") && !path.startsWith("/api/runner/pair"));
    }

    static boolean platformPath(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/platform/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
                auth.authenticate(header.substring(PREFIX.length()))
                        // La conexión de agentes locales solo abre el servidor MCP y la descarga del kit.
                        .filter(who -> !who.runner() || runnerPath(request))
                        .ifPresent(who -> {
                    TenantContext.set(who.workspaceId());
                    request.setAttribute(AuditHints.ACTOR, new AuditHints.Actor(who.workspaceId(),
                            who.memberId(), who.name(), who.agent() ? AuditHints.Kind.AGENT : AuditHints.Kind.HUMAN));
                    var kind = new SimpleGrantedAuthority(who.runner() ? "ROLE_RUNNER" : who.agent() ? "ROLE_AGENT" : "ROLE_HUMAN");
                    // ROLE_EDITOR: puede escribir en el tablero. Quien solo mira (y la conexión de agentes locales) no lo recibe.
                    var authorities = new java.util.ArrayList<SimpleGrantedAuthority>(List.of(kind));
                    if (!who.viewOnly() && !who.runner()) authorities.add(new SimpleGrantedAuthority("ROLE_EDITOR"));
                    // ROLE_ADMIN / ROLE_OWNER: administra el espacio / además el plan y los pagos (solo personas con su sesión).
                    if (who.administers()) authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                    if (who.owner()) authorities.add(new SimpleGrantedAuthority("ROLE_OWNER"));
                    // ROLE_PLATFORM: administra planes y licencias; se consulta solo en esas rutas.
                    if (!who.agent() && !who.runner() && platformPath(request) && platform.isAdmin(who.memberId())) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_PLATFORM"));
                    }
                    SecurityContextHolder.getContext().setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(who, null, authorities));
                });
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            ec.kuador.board.shared.tenant.BoardScope.clear();
        }
    }
}
