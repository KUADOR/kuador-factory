package ec.kuador.board.boardaccess;

import ec.kuador.board.attachment.AttachmentApi;
import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.docs.DocsApi;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.BoardScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Fija los tableros visibles de quien pide y corta, con 404 (no se revela que existe), lo que es de un tablero
 * ajeno: {@code /api/projects/{clave}/…}, {@code /api/issues/{CLAVE-n}/…}, {@code /api/docs/{id}/…} y
 * {@code /api/attachments/{id}/…}. Los listados y el MCP filtran con el mismo {@link BoardScope}.
 */
@Component
class BoardAccessInterceptor implements HandlerInterceptor {

    private static final Pattern PROJECT = Pattern.compile("^/api/projects/([^/]+)(?:/.*)?$");
    private static final Pattern ISSUE = Pattern.compile("^/api/issues/([^/]+)(?:/.*)?$");
    private static final Pattern DOC = Pattern.compile("^/api/docs/([0-9a-fA-F-]{36})(?:/.*)?$");
    private static final Pattern ATTACHMENT = Pattern.compile("^/api/attachments/([0-9a-fA-F-]{36})(?:/.*)?$");

    private final ProjectApi projects;
    private final DocsApi docs;
    private final AttachmentApi attachments;

    BoardAccessInterceptor(ProjectApi projects, DocsApi docs, AttachmentApi attachments) {
        this.projects = projects;
        this.docs = docs;
        this.attachments = attachments;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        BoardScope.clear();
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedMember who)) return true;
        // Los agentes trabajan donde los suman (project_agent); quien administra ve todo.
        if (who.agent() || who.access().administers()) return true;
        BoardScope.set(projects.boardsOf(who.memberId()));
        String path = request.getRequestURI().substring(request.getContextPath().length());
        target(path).ifPresent(key -> {
            if (!BoardScope.allows(key)) throw new NotFoundException("No existe el proyecto " + key + ".");
        });
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        BoardScope.clear();
    }

    /** El tablero al que apunta la ruta, si apunta a uno. */
    private Optional<String> target(String path) {
        Matcher m = PROJECT.matcher(path);
        if (m.matches()) return Optional.of(m.group(1).toUpperCase(Locale.ROOT));
        m = ISSUE.matcher(path);
        if (m.matches()) return Optional.of(BoardScope.projectOf(m.group(1).toUpperCase(Locale.ROOT)));
        m = DOC.matcher(path);
        if (m.matches()) return uuid(m.group(1)).flatMap(docs::projectOf);
        m = ATTACHMENT.matcher(path);
        if (m.matches()) return uuid(m.group(1)).flatMap(attachments::issueOf).map(BoardScope::projectOf);
        return Optional.empty();
    }

    private static Optional<UUID> uuid(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
