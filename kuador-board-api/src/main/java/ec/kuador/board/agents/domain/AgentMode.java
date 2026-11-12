package ec.kuador.board.agents.domain;

/** Dónde trabaja un agente. */
public enum AgentMode {
    /** Desde el cliente de IA de una persona (Claude Code, Codex) por el servidor MCP del tablero, con su suscripción. */
    LOCAL,
    /** En el tablero, con la clave de IA de la empresa: trabaja solo cuando una persona le asigna una tarjeta. */
    SERVER
}
