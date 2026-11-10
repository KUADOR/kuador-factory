package ec.kuador.board.issue;

import java.time.Instant;
import java.util.UUID;

public record IssueDeleted(
        UUID eventId, UUID workspaceId, String issueKey, String projectKey, UUID actorId, Instant occurredAt,
        String title) implements IssueEvent {}
