package ec.kuador.board.issue;

import java.time.Instant;
import java.util.UUID;

public record IssueCreated(
        UUID eventId, UUID workspaceId, String issueKey, String projectKey, UUID actorId, Instant occurredAt,
        String title, String type, String status, String category) implements IssueEvent {}
