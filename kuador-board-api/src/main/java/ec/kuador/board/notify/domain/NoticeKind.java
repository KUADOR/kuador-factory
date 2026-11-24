package ec.kuador.board.notify.domain;

/** Por qué se avisa a una persona. */
public enum NoticeKind {
    /** Le asignaron un issue (p. ej. un agente le pide una decisión). */
    ASSIGNED,
    /** La nombraron con «@» en un comentario. Sigue la preferencia de las asignaciones. */
    MENTION,
    /** Un issue espera su aprobación en una puerta. */
    GATE,
    /** Un agente revisor cerró una puerta: la aprobó (y fusionó) o la devolvió. Sigue la preferencia de las puertas. */
    REVIEW,
    /** El reparto de tareas de una épica espera su aprobación. */
    PLAN,
    /** Cobros: el plan vence pronto, venció o llegó un pago (recibo). Siempre se envía. */
    BILLING,
    /** El gasto de IA llegó al 80 % o al 100 % de un tope del mes. Siempre se envía a quien administra. */
    BUDGET,
    /** Prueba pedida por la propia persona. */
    TEST
}
