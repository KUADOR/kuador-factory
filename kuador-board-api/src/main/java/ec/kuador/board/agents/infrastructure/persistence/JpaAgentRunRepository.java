package ec.kuador.board.agents.infrastructure.persistence;

import ec.kuador.board.agents.domain.AgentRun;
import ec.kuador.board.agents.domain.AgentRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

interface SpringDataAgentRuns extends JpaRepository<AgentRunEntity, UUID> {

    boolean existsByTriggerEventId(UUID eventId);

    @Query("""
            select r from AgentRunEntity r
            where (:agentId is null or r.agentId = :agentId) and (:issueKey is null or r.issueKey = :issueKey)
            order by r.createdAt desc
            """)
    List<AgentRunEntity> findRecent(UUID agentId, String issueKey, Limit limit);

    List<AgentRunEntity> findByStatusAndCreatedAtBefore(AgentRun.Status status, Instant before);

    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from AgentRunEntity r where r.workspaceId = :workspaceId and r.projectKey = :projectKey")
    void deleteByProject(UUID workspaceId, String projectKey);
}

@Repository
class JpaAgentRunRepository implements AgentRunRepository {

    private final SpringDataAgentRuns jpa;

    JpaAgentRunRepository(SpringDataAgentRuns jpa) {
        this.jpa = jpa;
    }

    @Override
    public void deleteByProject(UUID workspaceId, String projectKey) {
        jpa.deleteByProject(workspaceId, projectKey);
    }

    @Override
    public boolean existsByTriggerEventId(UUID eventId) {
        return jpa.existsByTriggerEventId(eventId);
    }

    @Override
    public void save(AgentRun run) {
        jpa.save(AgentRunEntity.from(run));
    }

    @Override
    public List<AgentRun> findRecent(UUID agentId, String issueKey, int limit) {
        return jpa.findRecent(agentId, issueKey, Limit.of(limit)).stream().map(AgentRunEntity::toDomain).toList();
    }

    @Override
    public List<AgentRun> findStuck(Instant before) {
        return jpa.findByStatusAndCreatedAtBefore(AgentRun.Status.RUNNING, before).stream().map(AgentRunEntity::toDomain).toList();
    }
}
