package ec.kuador.board.audit.infrastructure.web;

import ec.kuador.board.audit.application.AuditLog;
import ec.kuador.board.audit.domain.AuditActions;
import ec.kuador.board.shared.web.AuditHints;
import ec.kuador.board.shared.web.WebSecret;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Envuelve toda la cadena (también la de seguridad) y, al terminar, registra las escrituras y las descargas con
 * quién, desde dónde y cómo terminó: así quedan también los intentos rechazados (401, 403, 429). Nunca lee el cuerpo.
 */
class AuditFilter extends OncePerRequestFilter {

    private final AuditLog audit;
    /** La IP y el navegador que reenvía la web cuentan solo si la petición trae el secreto de la web. */
    private final WebSecret web;

    AuditFilter(AuditLog audit, WebSecret web) {
        this.audit = audit;
        this.web = web;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // El servidor MCP y el webhook de Telegram tienen su propio rastro (actividad del tablero, mensajes).
        boolean api = path.startsWith("/api/") || path.equals("/oauth/token");
        return !api || path.startsWith("/api/telegram/webhook") || request.getMethod().equals("OPTIONS") || request.getMethod().equals("HEAD");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            record(request, response.getStatus());
        }
    }

    private void record(HttpServletRequest request, int status) {
        String method = request.getMethod();
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        AuditActions.Match resolved = pattern != null ? new AuditActions.Match(method + " " + pattern, java.util.List.of()) : AuditActions.resolve(method, path);
        String action = resolved.action();
        boolean denied = status == 401 || status == 403;
        if (!AuditActions.audited(method, action) && !(denied && status == 403)) return;

        AuditHints.Actor actor = request.getAttribute(AuditHints.ACTOR) instanceof AuditHints.Actor a ? a : null;
        String target = request.getAttribute(AuditHints.SUBJECT) instanceof String s ? s
                : !resolved.values().isEmpty() ? String.join(" · ", resolved.values()) : target(request);
        audit.record(new AuditLog.Record(actor == null ? null : actor.workspaceId(), actor == null ? null : actor.memberId(),
                actor == null ? null : actor.name(), (actor == null ? AuditHints.Kind.ANONYMOUS : actor.kind()).name(), method, cut(action, 120),
                cut(target, 200), status, cut(web.clientIp(request), 64), cut(web.clientAgent(request), 300)));
    }

    /** Los valores de la ruta (p. ej. VEN-12, o el código del plan). */
    @SuppressWarnings("unchecked")
    private static String target(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(vars instanceof Map<?, ?> map) || map.isEmpty()) return null;
        return String.join(" · ", new TreeMap<>((Map<String, String>) map).values());
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
