package ec.kuador.board.agents.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AgentRunRepository {

    boolean existsByTriggerEventId(UUID eventId);

    void save(AgentRun run);

    List<AgentRun> findRecent(UUID agentId, String issueKey, int limit);

    /** Ejecuciones que quedaron en RUNNING antes de {@code before} (la API se reinició a mitad). */
    List<AgentRun> findStuck(Instant before);
}
