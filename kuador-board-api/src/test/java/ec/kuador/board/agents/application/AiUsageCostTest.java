package ec.kuador.board.agents.application;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.agents.domain.AiUsageStore;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Lo leído del caché de prompts cuesta ~10% de la entrada y lo guardado ~125%: el costo y el tope lo reflejan. */
class AiUsageCostTest {

    @Test
    void cachedTokensAreChargedAtTheirShare() {
        var price = new AiUsageStore.Price("claude-opus-5-5", new BigDecimal("4"), new BigDecimal("20"));
        // 100k sin caché + 1M leídos (a 10%) + 100k guardados (a 125%) = 325k de entrada; 10k de salida.
        var usage = new AiUsageStore.Usage(UUID.randomUUID(), "claude-opus-5-5", 1, 100_000, 10_000, 1_000_000, 100_000);

        assertThat(AiUsage.cost(price, usage)).isEqualByComparingTo("1.5"); // 0.325 × 4 + 0.01 × 20
    }
}
