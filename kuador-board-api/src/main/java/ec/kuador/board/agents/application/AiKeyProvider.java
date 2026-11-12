package ec.kuador.board.agents.application;

import java.util.Optional;

/** La clave de IA del espacio actual (la de la empresa), si la configuró. */
@FunctionalInterface
public interface AiKeyProvider {

    Optional<String> currentKey();

    /** Si el espacio llegó a su tope mensual de IA. */
    default boolean overBudget() {
        return false;
    }
}
