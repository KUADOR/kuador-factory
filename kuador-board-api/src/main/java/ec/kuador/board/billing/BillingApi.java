package ec.kuador.board.billing;

import java.util.UUID;

/** API pública del módulo: límites del plan del espacio actual y alta de la suscripción. */
public interface BillingApi {

    /** Lo que limita un plan. */
    enum Limit {
        MEMBERS,
        PROJECTS
    }

    /** Falla con un mensaje claro si crear uno más de {@code limit} supera el plan; {@code current} es cuántos hay hoy. */
    void requireRoom(Limit limit, long current);

    /** Falla si el plan vigente no incluye la fábrica de software; {@code what} nombra lo que se intentó. */
    void requireFactory(String what);

    /** Primer plan de un espacio recién creado (los de pago empiezan con su prueba, si tienen). */
    void subscribe(UUID workspaceId, String planCode);
}
