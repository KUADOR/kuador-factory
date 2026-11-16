package ec.kuador.board.agents;

import java.util.UUID;

/**
 * Un agente empezó o terminó un trabajo (bien, con preguntas, fallido u omitido). Lo escucha la vista en vivo (la
 * oficina y el tablero) para mostrar quién está trabajando. {@code status}: RUNNING, SUCCEEDED, FAILED o SKIPPED.
 */
public record AgentRunChanged(UUID workspaceId, String projectKey, String issueKey, UUID agentId, String status) {}
