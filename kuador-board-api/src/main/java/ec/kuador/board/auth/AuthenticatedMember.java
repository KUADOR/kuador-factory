package ec.kuador.board.auth;

import ec.kuador.board.member.MemberAccess;
import java.util.UUID;

/**
 * Quién hace la petición y en qué espacio. Lo resuelve el filtro de seguridad a partir del token.
 * {@code access}: su nivel en el espacio. {@code runner}: es la conexión de agentes locales de esa
 * persona (su cliente de IA; solo sirve para el servidor MCP del tablero). {@code run}: si es la credencial de una sola
 * ejecución del runner, la tarjeta y el rol a los que se limita (nulo en los demás).
 */
public record AuthenticatedMember(UUID memberId, UUID workspaceId, String name, boolean agent, UUID tokenId, MemberAccess access, boolean runner,
        RunScope run) {

    public AuthenticatedMember {
        if (access == null) access = MemberAccess.EDIT;
    }

    public AuthenticatedMember(UUID memberId, UUID workspaceId, String name, boolean agent, UUID tokenId, MemberAccess access, boolean runner) {
        this(memberId, workspaceId, name, agent, tokenId, access, runner, null);
    }

    /** Solo lectura. */
    public boolean viewOnly() {
        return access == MemberAccess.VIEW;
    }

    /** Persona que administra el espacio (dueña o administradora), con su sesión, no un agente ni su conexión local. */
    public boolean administers() {
        return !agent && !runner && access.administers();
    }

    /** Persona dueña del espacio, con su sesión. */
    public boolean owner() {
        return !agent && !runner && access == MemberAccess.OWNER;
    }
}
