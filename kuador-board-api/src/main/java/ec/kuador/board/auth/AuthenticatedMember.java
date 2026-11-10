package ec.kuador.board.auth;

import java.util.UUID;

/**
 * Quién hace la petición y en qué espacio. Lo resuelve el filtro de seguridad a partir del token.
 * {@code viewOnly}: su acceso es de solo lectura. {@code runner}: es la conexión de Claude Code de
 * esa persona (solo sirve para el servidor MCP del tablero).
 */
public record AuthenticatedMember(UUID memberId, UUID workspaceId, String name, boolean agent, UUID tokenId, boolean viewOnly, boolean runner) {

    public AuthenticatedMember(UUID memberId, UUID workspaceId, String name, boolean agent, UUID tokenId, boolean viewOnly) {
        this(memberId, workspaceId, name, agent, tokenId, viewOnly, false);
    }

    public AuthenticatedMember(UUID memberId, UUID workspaceId, String name, boolean agent, UUID tokenId) {
        this(memberId, workspaceId, name, agent, tokenId, false, false);
    }
}
