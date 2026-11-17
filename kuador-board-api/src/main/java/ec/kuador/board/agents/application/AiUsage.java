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

    /** Corridas de agentes por espacio en una hora: frena un bucle o a alguien que dispara corridas sin parar. */
    private final int maxRunsPerHour;

    AiUsage(AiUsageStore store, MemberApi members, Clock clock, @Value("${board.billing.zone:America/Guayaquil}") String zone,
            @Value("${board.agents.max-runs-per-hour:60}") int maxRunsPerHour) {
        this.maxRunsPerHour = maxRunsPerHour;
        this.store = store;
        this.members = members;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    /** {@code cachedTokens}: tokens de entrada que salieron del caché de prompts (cuestan ~10%). */
    public record AgentUsage(UUID agentId, String name, long runs, long inputTokens, long outputTokens, long cachedTokens, BigDecimal costUsd) {}

    /** {@code costUsd} nulo si algún modelo usado no tiene precio configurado ({@code unpricedModels}). */
    public record Report(String month, long runs, long inputTokens, long outputTokens, long cachedTokens, BigDecimal costUsd, List<AgentUsage> byAgent,
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
            long[] t = tokens.computeIfAbsent(u.agentId(), k -> new long[4]);
            t[0] += u.runs();
            t[1] += u.inputTokens() + u.cacheReadTokens() + u.cacheWriteTokens();
            t[2] += u.outputTokens();
            t[3] += u.cacheReadTokens();
            AiUsageStore.Price price = prices.get(u.model());
            if (price == null) {
                if (!unpriced.contains(u.model())) unpriced.add(u.model());
                costs.put(u.agentId(), null);
            } else if (!costs.containsKey(u.agentId()) || costs.get(u.agentId()) != null) {
                costs.merge(u.agentId(), cost(price, u), BigDecimal::add);
            }
        }
        List<AgentUsage> byAgent = new ArrayList<>();
        long runs = 0;
        long in = 0;
        long out = 0;
        long cached = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (var e : tokens.entrySet()) {
            String name = members.find(e.getKey()).map(MemberView::name).orElse("Agente eliminado");
            BigDecimal c = costs.get(e.getKey());
            byAgent.add(new AgentUsage(e.getKey(), name, e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3], c == null ? null : c.setScale(2, RoundingMode.HALF_UP)));
            runs += e.getValue()[0];
            in += e.getValue()[1];
            out += e.getValue()[2];
            cached += e.getValue()[3];
            if (total != null) total = c == null ? null : total.add(c);
        }
        return new Report(month.toString(), runs, in, out, cached, total == null ? null : total.setScale(2, RoundingMode.HALF_UP), byAgent,
                store.budget(ws).orElse(null), unpriced);
    }

    /**
     * Lo que costó cada ejecución en el servidor, en USD, por id; sin entrada si es local, no gastó o su modelo no tiene
     * precio configurado.
     */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> costs(List<ec.kuador.board.agents.domain.AgentRun> runs) {
        Map<String, AiUsageStore.Price> prices = new LinkedHashMap<>();
        store.prices().forEach(p -> prices.put(p.model(), p));
        Map<UUID, BigDecimal> out = new LinkedHashMap<>();
        for (var r : runs) {
            if (r.isLocal() || r.model() == null || r.inputTokens() == null) continue;
            AiUsageStore.Price price = prices.get(r.model());
            if (price == null) continue;
            out.put(r.id(), cost(price, new AiUsageStore.Usage(r.agentId(), r.model(), 1, r.inputTokens(), nz(r.outputTokens()), nz(r.cacheReadTokens()),
                    nz(r.cacheWriteTokens()))).setScale(4, RoundingMode.HALF_UP));
        }
        return out;
    }

    private static long nz(Integer n) {
        return n == null ? 0 : n;
    }

    /** Si el espacio llegó a su tope mensual (solo se sabe con precios para todo lo usado). */
    @Transactional(readOnly = true)
    public boolean overBudget() {
        Optional<BigDecimal> budget = store.budget(TenantContext.require());
        if (budget.isEmpty()) return false;
        Report r = report();
        // Con tope y un modelo usado sin precio no se puede saber el gasto: se frena por precaución (antes el tope dejaba de aplicar).
        return r.costUsd() == null || r.costUsd().compareTo(budget.get()) >= 0;
    }

    /** Si el espacio ya hizo el máximo de corridas de la última hora. */
    @Transactional(readOnly = true)
    public boolean overRate() {
        if (maxRunsPerHour <= 0) return false;
        Instant now = clock.instant();
        long runs = store.usage(TenantContext.require(), now.minus(java.time.Duration.ofHours(1)), now).stream().mapToLong(AiUsageStore.Usage::runs).sum();
        return runs >= maxRunsPerHour;
    }

    public int maxRunsPerHour() {
        return maxRunsPerHour;
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

    /** Leer del caché de prompts cuesta ~10% de la entrada; guardar en él (5 minutos), ~125%. */
    static final BigDecimal CACHE_READ = new BigDecimal("0.10");
    static final BigDecimal CACHE_WRITE = new BigDecimal("1.25");

    static BigDecimal cost(AiUsageStore.Price p, AiUsageStore.Usage u) {
        BigDecimal input = BigDecimal.valueOf(u.inputTokens())
                .add(BigDecimal.valueOf(u.cacheReadTokens()).multiply(CACHE_READ))
                .add(BigDecimal.valueOf(u.cacheWriteTokens()).multiply(CACHE_WRITE));
        return p.inputPerMTok().multiply(input).add(p.outputPerMTok().multiply(BigDecimal.valueOf(u.outputTokens()))).divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
