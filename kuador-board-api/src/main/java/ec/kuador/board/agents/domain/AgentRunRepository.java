package ec.kuador.board.agents.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AgentRunRepository {

    boolean existsByTriggerEventId(UUID eventId);

    java.util.Optional<AgentRun> findById(UUID id);

    void save(AgentRun run);

    /** Borra las corridas de un tablero borrado. */
    void deleteByProject(java.util.UUID workspaceId, String projectKey);

    List<AgentRun> findRecent(UUID agentId, String issueKey, int limit);

    /** Ejecuciones que quedaron en RUNNING antes de {@code before} (la API se reinició a mitad). */
    List<AgentRun> findStuck(Instant before);
}
