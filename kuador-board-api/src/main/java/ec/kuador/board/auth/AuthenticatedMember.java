package ec.kuador.board.auth;

import ec.kuador.board.member.MemberAccess;
import java.util.UUID;

/**
 * Quién hace la petición y en qué espacio. Lo resuelve el filtro de seguridad a partir del token.
 * {@code access}: su nivel en el espacio. {@code runner}: es la conexión de agentes locales de esa
 * persona (su cliente de IA; solo sirve para el servidor MCP del tablero).
 */
public record AuthenticatedMember(UUID memberId, UUID workspaceId, String name, boolean agent, UUID tokenId, MemberAccess access, boolean runner) {

    public AuthenticatedMember {
        if (access == null) access = MemberAccess.EDIT;
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
