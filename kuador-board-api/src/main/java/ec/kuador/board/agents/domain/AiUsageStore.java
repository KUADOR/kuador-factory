package ec.kuador.board.agents.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Consumo de IA del espacio (desde las ejecuciones de agentes en el tablero), precios por modelo y tope mensual. */
public interface AiUsageStore {

    /** Ejecuciones con consumo de un agente y modelo en el periodo. */
    /** {@code inputTokens}: los que no salieron del caché; aparte, los leídos del caché y los guardados en él. */
    record Usage(UUID agentId, String model, long runs, long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {

        public Usage(UUID agentId, String model, long runs, long inputTokens, long outputTokens) {
            this(agentId, model, runs, inputTokens, outputTokens, 0, 0);
        }
    }

    List<Usage> usage(UUID workspaceId, Instant from, Instant to);

    record Price(String model, BigDecimal inputPerMTok, BigDecimal outputPerMTok) {}

    List<Price> prices();

    void savePrice(Price price, Instant now);

    void deletePrice(String model);

    Optional<BigDecimal> budget(UUID workspaceId);

    void saveBudget(UUID workspaceId, BigDecimal budget);

    // ---- Freno: por tarjeta, por agente y avisos ----

    /** Consumo de una tarjeta (todas sus ejecuciones en el servidor), por agente y modelo. */
    List<Usage> issueUsage(UUID workspaceId, String issueKey);

    Optional<BigDecimal> cardCap(UUID workspaceId);

    void saveCardCap(UUID workspaceId, BigDecimal cap);

    java.util.Map<UUID, BigDecimal> agentBudgets(UUID workspaceId);

    void saveAgentBudget(UUID workspaceId, UUID agentId, BigDecimal monthly);

    /** Marca el aviso como enviado; falso si ya se había enviado (se avisa una sola vez por mes, tope y nivel). */
    boolean markAlert(UUID workspaceId, String scope, String month, int level, Instant now);
}
