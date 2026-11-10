package ec.kuador.board.issue;

import java.time.Instant;
import java.util.UUID;

/** {@code excerpt}: primeros 280 caracteres; el texto completo se lee en /api/issues/{key}/comments. */
public record IssueCommented(
        UUID eventId, UUID workspaceId, String issueKey, String projectKey, UUID actorId, Instant occurredAt,
        UUID commentId, String excerpt) implements IssueEvent {}
