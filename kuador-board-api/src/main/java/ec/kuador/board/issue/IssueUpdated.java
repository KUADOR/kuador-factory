package ec.kuador.board.issue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Edición de campos. No incluye estado ni responsable: esos cambios tienen su propio evento
 * ({@link IssueMoved}, {@link IssueAssigned}).
 */
public record IssueUpdated(
        UUID eventId, UUID workspaceId, String issueKey, String projectKey, UUID actorId, Instant occurredAt,
        List<Change> changes) implements IssueEvent {

    /** {@code field}: title, description, type, priority, storyPoints, parent, labels o subject (el proyecto). La descripción no viaja (from/to nulos). */
    public record Change(String field, String from, String to) {}
}
