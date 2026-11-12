package ec.kuador.board.project;

import ec.kuador.board.shared.tenant.WorkspaceScoped;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Se creó un tablero. {@code teamRoles}: roles de agentes que su plantilla trae por defecto. */
public record ProjectCreated(UUID eventId, UUID workspaceId, String projectKey, List<String> teamRoles, Instant occurredAt) implements WorkspaceScoped {}
