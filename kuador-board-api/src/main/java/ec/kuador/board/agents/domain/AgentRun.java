package ec.kuador.board.agents.domain;

import java.time.Instant;
import java.util.UUID;

/** Una ejecución de un agente sobre un issue. */
public record AgentRun(
        UUID id, UUID agentId, String issueKey, String projectKey, UUID triggerEventId, UUID requestedBy, Status status,
        String model, Integer inputTokens, Integer outputTokens, Integer cacheReadTokens, Integer cacheWriteTokens, String summary, String error,
        Instant createdAt, Instant finishedAt) {

    public enum Status {
        RUNNING,
        SUCCEEDED,
        FAILED,
        /** No había nada que hacer o los agentes están desactivados; {@code summary} dice por qué. */
        SKIPPED
    }

    /** Prefijo del «modelo» de las ejecuciones que corren en el cliente de IA de una persona (no en el servidor). */
    public static final String LOCAL_PREFIX = "local:";

    /** Corre en la máquina de una persona: {@code local:codex}, {@code local:claude-code}... */
    public boolean isLocal() {
        return model != null && (model.startsWith(LOCAL_PREFIX) || model.equals("claude-code"));
    }

    public static AgentRun startLocal(UUID agentId, String issueKey, String projectKey, UUID supervisorId, String client, Instant now) {
        String label = LOCAL_PREFIX + (client == null || client.isBlank() ? "mcp" : client);
        // Sin evento que la dispare: su propio identificador ocupa el lugar (la columna es única y obligatoria).
        return new AgentRun(UUID.randomUUID(), agentId, issueKey, projectKey, UUID.randomUUID(), supervisorId, Status.RUNNING, label.length() > 60 ? label.substring(0, 60) : label, null, null,
                null, null, null, null, now, null);
    }

    public static AgentRun start(UUID agentId, String issueKey, String projectKey, UUID eventId, UUID requestedBy, Instant now) {
        return new AgentRun(UUID.randomUUID(), agentId, issueKey, projectKey, eventId, requestedBy, Status.RUNNING, null, null, null,
                null, null, null, null, now, null);
    }

    public AgentRun succeeded(String model, long input, long output, String summary, Instant now) {
        return succeeded(model, input, output, 0, 0, summary, now);
    }

    /**
     * {@code input}: tokens de entrada que no salieron del caché; {@code cacheRead} y {@code cacheWrite}: los que se leyeron
     * del caché de prompts (cuestan ~10%) y los que se guardaron en él (cuestan ~125%).
     */
    public AgentRun succeeded(String model, long input, long output, long cacheRead, long cacheWrite, String summary, Instant now) {
        return new AgentRun(id, agentId, issueKey, projectKey, triggerEventId, requestedBy, Status.SUCCEEDED, model, (int) input,
                (int) output, (int) cacheRead, (int) cacheWrite, summary, null, createdAt, now);
    }

    public AgentRun failed(String error, Instant now) {
        return new AgentRun(id, agentId, issueKey, projectKey, triggerEventId, requestedBy, Status.FAILED, model, inputTokens,
                outputTokens, cacheReadTokens, cacheWriteTokens, summary, error, createdAt, now);
    }

    public AgentRun skipped(String reason, Instant now) {
        return new AgentRun(id, agentId, issueKey, projectKey, triggerEventId, requestedBy, Status.SKIPPED, null, null, null, null, null, reason,
                null, createdAt, now);
    }
}
