package ec.kuador.board.auth.domain;

public enum TokenKind {
    /** Sesión de una persona, con vencimiento. */
    SESSION,
    /** Token de servicio de un agente, vigente hasta que se revoque. */
    AGENT
}
