package ec.kuador.board.shared.web;

import java.util.UUID;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Pistas para la auditoría que deja quien atiende la petición: quién la hizo (el filtro de autenticación, o el login
 * al acertar) y sobre quién o qué (p. ej. el correo de un intento de login). Viajan como atributos de la petición, así
 * ningún módulo depende del de auditoría. Nunca contraseñas, claves ni contenidos.
 */
public final class AuditHints {

    public static final String ACTOR = AuditHints.class.getName() + ".actor";
    public static final String SUBJECT = AuditHints.class.getName() + ".subject";

    public enum Kind {
        HUMAN,
        AGENT,
        SYSTEM,
        ANONYMOUS
    }

    public record Actor(UUID workspaceId, UUID memberId, String name, Kind kind) {}

    private AuditHints() {}

    public static void actor(Actor actor) {
        set(ACTOR, actor);
    }

    /** Sobre qué fue la acción cuando no está en la ruta (p. ej. el correo con el que se intentó entrar). */
    public static void subject(String subject) {
        if (subject != null && !subject.isBlank()) set(SUBJECT, subject.strip().length() > 200 ? subject.strip().substring(0, 200) : subject.strip());
    }

    private static void set(String key, Object value) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) attrs.setAttribute(key, value, RequestAttributes.SCOPE_REQUEST);
    }
}
