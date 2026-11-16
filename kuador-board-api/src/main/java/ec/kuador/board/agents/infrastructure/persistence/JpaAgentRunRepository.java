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
    private final org.springframework.context.ApplicationEventPublisher events;

    JpaAgentRunRepository(SpringDataAgentRuns jpa, org.springframework.context.ApplicationEventPublisher events) {
        this.jpa = jpa;
        this.events = events;
    }

    @Override
    public void deleteByProject(UUID workspaceId, String projectKey) {
        jpa.deleteByProject(workspaceId, projectKey);
    }

    @Override
    public java.util.Optional<AgentRun> findById(UUID id) {
        return jpa.findById(id).map(AgentRunEntity::toDomain);
    }

    @Override
    public boolean existsByTriggerEventId(UUID eventId) {
        return jpa.existsByTriggerEventId(eventId);
    }

    @Override
    public void save(AgentRun run) {
        AgentRunEntity saved = jpa.save(AgentRunEntity.from(run));
        // Para la vista en vivo: quién empezó o terminó de trabajar. El espacio sale del contexto (el @TenantId de la
        // entidad recién se llena al escribir en la base).
        java.util.UUID workspace = ec.kuador.board.shared.tenant.TenantContext.current();
        if (workspace.equals(ec.kuador.board.shared.tenant.TenantContext.NONE) || workspace.equals(ec.kuador.board.shared.tenant.TenantContext.ROOT)) {
            workspace = saved.workspaceId;
        }
        if (workspace != null) {
            events.publishEvent(new ec.kuador.board.agents.AgentRunChanged(workspace, run.projectKey(), run.issueKey(), run.agentId(), run.status().name()));
        }
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
