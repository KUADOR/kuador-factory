package ec.kuador.board.agents.application;

import java.util.Optional;

/** La clave de IA del espacio actual (la de la empresa), si la configuró. */
@FunctionalInterface
public interface AiKeyProvider {

    Optional<String> currentKey();

    /**
     * El modelo con que trabaja ese agente con la clave del espacio: el elegido para él si el proveedor lo ofrece; si
     * no, el del espacio. Vacío sin clave de Anthropic en el espacio (rige el del servidor).
     */
    default Optional<String> modelForAgent(java.util.UUID agentId) {
        return Optional.empty();
    }

    /** Cuánto razona el agente (low…max) con la clave de la empresa, si se eligió o lo sugiere su rol. */
    default Optional<String> effortForAgent(java.util.UUID agentId) {
        return Optional.empty();
    }

    /** Si el espacio llegó a su tope mensual de IA. */
    default boolean overBudget() {
        return false;
    }

    /** Por qué ese agente no puede trabajar esa tarjeta por presupuesto (el espacio, su tope o el de la tarjeta); vacío = puede. */
    default Optional<String> budgetBlock(java.util.UUID agentId, String issueKey) {
        return overBudget() ? Optional.of("El espacio llegó a su tope mensual de IA (Conexiones → IA).") : Optional.empty();
    }

    /** Si el espacio ya hizo el máximo de corridas de agentes de la última hora. */
    default boolean overRate() {
        return false;
    }
}
