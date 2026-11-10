package ec.kuador.board.member;

import java.util.UUID;

/**
 * Vista pública de un miembro para otros módulos. {@code agent} distingue agentes de personas;
 * {@code role} es, para un agente, la clave de su rol en el catálogo; {@code description} su
 * especialidad.
 */
public record MemberView(UUID id, UUID workspaceId, String name, boolean agent, String role, String avatarColor, String description) {}
