package ec.kuador.board.auth.domain;

public enum TokenKind {
    /** Sesión de una persona, con vencimiento. */
    SESSION,
    /** Token de servicio de un agente, vigente hasta que se revoque. */
    AGENT,
    /**
     * Conexión de agentes locales de una persona (su cliente de IA con MCP): solo abre el servidor MCP del tablero, donde actúa como
     * los agentes del espacio y solo sobre los issues que esa persona supervisa.
     */
    RUNNER
}
