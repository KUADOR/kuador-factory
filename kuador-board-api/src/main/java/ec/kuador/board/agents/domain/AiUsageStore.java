package ec.kuador.board.agents.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Consumo de IA del espacio (desde las ejecuciones de agentes en el tablero), precios por modelo y tope mensual. */
public interface AiUsageStore {

    /** Ejecuciones con consumo de un agente y modelo en el periodo. */
    record Usage(UUID agentId, String model, long runs, long inputTokens, long outputTokens) {}

    List<Usage> usage(UUID workspaceId, Instant from, Instant to);

    record Price(String model, BigDecimal inputPerMTok, BigDecimal outputPerMTok) {}

    List<Price> prices();

    void savePrice(Price price, Instant now);

    void deletePrice(String model);

    Optional<BigDecimal> budget(UUID workspaceId);

    void saveBudget(UUID workspaceId, BigDecimal budget);
}
