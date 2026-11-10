package ec.kuador.board.issue;

import java.time.Instant;
import java.util.UUID;

/**
 * Cambio de columna. {@code fromStatus}/{@code toStatus} son claves de columna del proyecto; las
 * categorías (TODO, IN_PROGRESS, DONE) permiten reaccionar a cualquier flujo, se llame la columna
 * "Hecho" o "Ganado". Reordenar dentro de la misma columna no lo emite.
 */
public record IssueMoved(
        UUID eventId, UUID workspaceId, String issueKey, String projectKey, UUID actorId, Instant occurredAt,
        String fromStatus, String toStatus, String fromCategory, String toCategory) implements IssueEvent {}
