package ec.kuador.board.agents;

import java.util.UUID;

/**
 * Registro del trabajo que hacen los agentes desde Claude Code (servidor MCP del tablero). Deja una
 * ejecución abierta mientras el agente trabaja, para que el tablero muestre quién está trabajando.
 */
public interface AgentWorkApi {

    /** El agente tomó el issue en el Claude Code de {@code supervisorId}. */
    void started(UUID agentId, String issueKey, String projectKey, UUID supervisorId);

    /** El agente terminó (entregó, devolvió o consultó): cierra su ejecución abierta con un resumen. */
    void finished(UUID agentId, String issueKey, String summary);
}
