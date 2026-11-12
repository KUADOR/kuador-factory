package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.AiUsageStore;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumo de IA del espacio: tokens de los agentes que trabajan en el tablero y su costo estimado con los precios por
 * modelo que configura la plataforma (USD por millón de tokens). Con un tope mensual, los agentes no arrancan al
 * llegar a él. Los agentes en la PC de una persona no cuentan: usan su suscripción.
 */
@Service
public class AiUsage {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final AiUsageStore store;
    private final MemberApi members;
    private final Clock clock;
    private final ZoneId zone;

    AiUsage(AiUsageStore store, MemberApi members, Clock clock, @Value("${board.billing.zone:America/Guayaquil}") String zone) {
        this.store = store;
        this.members = members;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    public record AgentUsage(UUID agentId, String name, long runs, long inputTokens, long outputTokens, BigDecimal costUsd) {}

    /** {@code costUsd} nulo si algún modelo usado no tiene precio configurado ({@code unpricedModels}). */
    public record Report(String month, long runs, long inputTokens, long outputTokens, BigDecimal costUsd, List<AgentUsage> byAgent,
            BigDecimal budgetUsd, List<String> unpricedModels) {}

    @Transactional(readOnly = true)
    public Report report() {
        UUID ws = TenantContext.require();
        YearMonth month = YearMonth.now(clock.withZone(zone));
        Instant from = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        Map<String, AiUsageStore.Price> prices = new LinkedHashMap<>();
        store.prices().forEach(p -> prices.put(p.model(), p));
        Map<UUID, long[]> tokens = new LinkedHashMap<>();
        Map<UUID, BigDecimal> costs = new LinkedHashMap<>();
        List<String> unpriced = new ArrayList<>();
        for (AiUsageStore.Usage u : store.usage(ws, from, to)) {
            long[] t = tokens.computeIfAbsent(u.agentId(), k -> new long[3]);
            t[0] += u.runs();
            t[1] += u.inputTokens();
            t[2] += u.outputTokens();
            AiUsageStore.Price price = prices.get(u.model());
            if (price == null) {
                if (!unpriced.contains(u.model())) unpriced.add(u.model());
                costs.put(u.agentId(), null);
            } else if (!costs.containsKey(u.agentId()) || costs.get(u.agentId()) != null) {
                costs.merge(u.agentId(), cost(price, u.inputTokens(), u.outputTokens()), BigDecimal::add);
            }
        }
        List<AgentUsage> byAgent = new ArrayList<>();
        long runs = 0;
        long in = 0;
        long out = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (var e : tokens.entrySet()) {
            String name = members.find(e.getKey()).map(MemberView::name).orElse("Agente eliminado");
            BigDecimal c = costs.get(e.getKey());
            byAgent.add(new AgentUsage(e.getKey(), name, e.getValue()[0], e.getValue()[1], e.getValue()[2], c == null ? null : c.setScale(2, RoundingMode.HALF_UP)));
            runs += e.getValue()[0];
            in += e.getValue()[1];
            out += e.getValue()[2];
            if (total != null) total = c == null ? null : total.add(c);
        }
        return new Report(month.toString(), runs, in, out, total == null ? null : total.setScale(2, RoundingMode.HALF_UP), byAgent,
                store.budget(ws).orElse(null), unpriced);
    }

    /** Si el espacio llegó a su tope mensual (solo se sabe con precios para todo lo usado). */
    @Transactional(readOnly = true)
    public boolean overBudget() {
        Optional<BigDecimal> budget = store.budget(TenantContext.require());
        if (budget.isEmpty()) return false;
        Report r = report();
        return r.costUsd() != null && r.costUsd().compareTo(budget.get()) >= 0;
    }

    /** Tope mensual en USD ({@code null} = sin tope). Necesita la clave de IA configurada. */
    @Transactional
    public Report setBudget(BigDecimal budget, boolean keyConfigured) {
        if (!keyConfigured) throw new DomainException("Primero guarda la clave de IA de la empresa.");
        if (budget != null && budget.signum() <= 0) throw new DomainException("El tope es un monto mayor que cero, o vacío para no tener tope.");
        store.saveBudget(TenantContext.require(), budget == null ? null : budget.setScale(2, RoundingMode.HALF_UP));
        return report();
    }

    // ---- Precios (plataforma) ----

    @Transactional(readOnly = true)
    public List<AiUsageStore.Price> prices() {
        return store.prices();
    }

    @Transactional
    public List<AiUsageStore.Price> savePrice(String model, BigDecimal input, BigDecimal output) {
        if (model == null || model.isBlank() || model.strip().length() > 60) throw new DomainException("Escribe el identificador del modelo, ej. claude-opus-5-5.");
        if (input == null || output == null || input.signum() < 0 || output.signum() < 0) throw new DomainException("Los precios son montos de 0 o más.");
        store.savePrice(new AiUsageStore.Price(model.strip(), input, output), clock.instant());
        return store.prices();
    }

    @Transactional
    public List<AiUsageStore.Price> deletePrice(String model) {
        store.deletePrice(model);
        return store.prices();
    }

    private static BigDecimal cost(AiUsageStore.Price p, long input, long output) {
        return p.inputPerMTok().multiply(BigDecimal.valueOf(input)).add(p.outputPerMTok().multiply(BigDecimal.valueOf(output))).divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
