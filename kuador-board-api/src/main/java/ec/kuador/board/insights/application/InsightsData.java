package ec.kuador.board.insights.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Lo que lee el dashboard (solo lectura, siempre de un espacio y de los tableros que se pasan). */
public interface InsightsData {

    record Card(String key, String projectKey, String title, String type, String status, Integer storyPoints, UUID assigneeId, UUID supervisorId,
            UUID reporterId, String parentKey, int reworkCount, Instant createdAt, Instant updatedAt) {}

    /** Un movimiento (MOVED: columnas), una asignación (ASSIGNED: ids) o el alta (CREATED: columna inicial). */
    record Event(String issueKey, String projectKey, UUID actorId, String type, String from, String to, Instant at) {}

    record Run(UUID agentId, String issueKey, String projectKey, String status, String model, long input, long output, long cacheRead, long cacheWrite,
            Instant createdAt, Instant finishedAt, UUID requestedBy) {}

    record Price(BigDecimal inputPerMTok, BigDecimal outputPerMTok) {}

    List<Card> cards(UUID workspaceId, Collection<String> projectKeys);

    List<Event> events(UUID workspaceId, Collection<String> projectKeys, Instant since);

    List<Run> runs(UUID workspaceId, Collection<String> projectKeys, Instant since);

    Map<String, Price> prices();

    Optional<BigDecimal> hoursPerPoint(UUID workspaceId);

    void saveHoursPerPoint(UUID workspaceId, BigDecimal hours, Instant now);
}
