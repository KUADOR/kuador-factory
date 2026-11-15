package ec.kuador.board.agents.domain;

/** Dónde trabaja un agente. */
public enum AgentMode {
    /** Desde el cliente de IA de una persona (Claude Code, Codex) por el servidor MCP del tablero, con su suscripción. */
    LOCAL,
    /** En el tablero, con la clave de IA de la empresa: trabaja cuando le asignan una tarjeta o cuando una llega a la etapa de su rol (cadena automática) y al terminar la pasa a la siguiente. */
    SERVER
}
