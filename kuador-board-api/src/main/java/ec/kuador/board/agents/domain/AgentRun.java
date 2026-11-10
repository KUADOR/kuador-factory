package ec.kuador.board.agents.domain;

import java.time.Instant;
import java.util.UUID;

/** Una ejecución de un agente sobre un issue. */
public record AgentRun(
        UUID id, UUID agentId, String issueKey, String projectKey, UUID triggerEventId, UUID requestedBy, Status status,
        String model, Integer inputTokens, Integer outputTokens, String summary, String error, Instant createdAt, Instant finishedAt) {

    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED,
        /** No había nada que hacer o los agentes están desactivados; {@code summary} dice por qué. */
        SKIPPED
    }

    public static AgentRun start(UUID agentId, String issueKey, String projectKey, UUID eventId, UUID requestedBy, Instant now) {
        return new AgentRun(UUID.randomUUID(), agentId, issueKey, projectKey, eventId, requestedBy, Status.RUNNING, null, null, null,
                null, null, now, null);
    }

    public AgentRun succeeded(String model, long input, long output, String summary, Instant now) {
        return new AgentRun(id, agentId, issueKey, projectKey, triggerEventId, requestedBy, Status.SUCCEEDED, model, (int) input,
                (int) output, summary, null, createdAt, now);
    }

    public AgentRun failed(String error, Instant now) {
        return new AgentRun(id, agentId, issueKey, projectKey, triggerEventId, requestedBy, Status.FAILED, model, inputTokens,
                outputTokens, summary, error, createdAt, now);
    }

    public AgentRun skipped(String reason, Instant now) {
        return new AgentRun(id, agentId, issueKey, projectKey, triggerEventId, requestedBy, Status.SKIPPED, null, null, null, reason,
                null, createdAt, now);
    }
}
