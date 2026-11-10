package ec.kuador.board.agents.infrastructure.persistence;

import ec.kuador.board.agents.domain.AgentRun;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "agent_run")
class AgentRunEntity {

    @Id
    UUID id;

    @TenantId
    @Column(name = "workspace_id")
    UUID workspaceId;

    @Column(name = "agent_id")
    UUID agentId;

    @Column(name = "issue_key")
    String issueKey;

    @Column(name = "project_key")
    String projectKey;

    @Column(name = "trigger_event_id")
    UUID triggerEventId;

    @Column(name = "requested_by")
    UUID requestedBy;

    @Enumerated(EnumType.STRING)
    AgentRun.Status status;

    String model;

    @Column(name = "input_tokens")
    Integer inputTokens;

    @Column(name = "output_tokens")
    Integer outputTokens;

    String summary;

    String error;

    @Column(name = "created_at")
    Instant createdAt;

    @Column(name = "finished_at")
    Instant finishedAt;

    protected AgentRunEntity() {}

    static AgentRunEntity from(AgentRun r) {
        AgentRunEntity e = new AgentRunEntity();
        e.id = r.id();
        e.agentId = r.agentId();
        e.issueKey = r.issueKey();
        e.projectKey = r.projectKey();
        e.triggerEventId = r.triggerEventId();
        e.requestedBy = r.requestedBy();
        e.status = r.status();
        e.model = r.model();
        e.inputTokens = r.inputTokens();
        e.outputTokens = r.outputTokens();
        e.summary = r.summary();
        e.error = r.error();
        e.createdAt = r.createdAt();
        e.finishedAt = r.finishedAt();
        return e;
    }

    AgentRun toDomain() {
        return new AgentRun(id, agentId, issueKey, projectKey, triggerEventId, requestedBy, status, model, inputTokens, outputTokens,
                summary, error, createdAt, finishedAt);
    }
}
