package ec.kuador.board.issue;

import java.time.Instant;
import java.util.UUID;

/** Cambio de responsable, por cualquier vía (crear, editar o asignar). {@code toAssigneeId} nulo = sin asignar. */
public record IssueAssigned(
        UUID eventId, UUID workspaceId, String issueKey, String projectKey, UUID actorId, Instant occurredAt,
        UUID fromAssigneeId, UUID toAssigneeId) implements IssueEvent {}
