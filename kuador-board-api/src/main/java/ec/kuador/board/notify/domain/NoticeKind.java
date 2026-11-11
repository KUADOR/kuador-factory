package ec.kuador.board.notify.domain;

/** Por qué se avisa a una persona. */
public enum NoticeKind {
    /** Le asignaron un issue (p. ej. un agente le pide una decisión). */
    ASSIGNED,
    /** Un issue espera su aprobación en una puerta. */
    GATE,
    /** El reparto de tareas de una épica espera su aprobación. */
    PLAN,
    /** Prueba pedida por la propia persona. */
    TEST
}
