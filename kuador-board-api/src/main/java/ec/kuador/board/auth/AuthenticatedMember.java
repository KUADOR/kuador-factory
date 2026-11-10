package ec.kuador.board.auth;

import java.util.UUID;

/**
 * Quién hace la petición y en qué espacio. Lo resuelve el filtro de seguridad a partir del token.
 * {@code viewOnly}: su acceso es de solo lectura.
 */
public record AuthenticatedMember(UUID memberId, UUID workspaceId, String name, boolean agent, UUID tokenId, boolean viewOnly) {

    public AuthenticatedMember(UUID memberId, UUID workspaceId, String name, boolean agent, UUID tokenId) {
        this(memberId, workspaceId, name, agent, tokenId, false);
    }
}
