package ec.kuador.board.member;

import java.util.UUID;

/**
 * Vista pública de un miembro para otros módulos. {@code agent} distingue agentes de personas;
 * {@code role} es, para un agente, la clave de su rol en el catálogo; {@code description} su
 * especialidad; {@code access}, si puede editar o solo mirar.
 */
public record MemberView(
        UUID id, UUID workspaceId, String name, boolean agent, String role, String avatarColor, String description, MemberAccess access) {

    /** Miembro con acceso de edición. */
    public MemberView(UUID id, UUID workspaceId, String name, boolean agent, String role, String avatarColor, String description) {
        this(id, workspaceId, name, agent, role, avatarColor, description, MemberAccess.EDIT);
    }

    public boolean viewOnly() {
        return access == MemberAccess.VIEW;
    }
}
