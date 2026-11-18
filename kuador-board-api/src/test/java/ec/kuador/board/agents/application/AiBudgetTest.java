package ec.kuador.board.agents.application;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.agents.AiBudgetAlert;
import ec.kuador.board.agents.domain.AiUsageStore;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.testing.InMemoryMembers;
import ec.kuador.board.testing.TestTenant;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Presupuesto con freno: el tope del espacio, el de cada agente y el de cada tarjeta detienen el trabajo; los avisos
 * salen al 80 % y al 100 %, una sola vez por mes y por tope.
 */
@ExtendWith(TestTenant.class)
class AiBudgetTest {

    static final UUID WS = TestTenant.WORKSPACE;
    static final UUID BACKEND = UUID.randomUUID();
    static final UUID QA = UUID.randomUUID();
    static final String MODEL = "claude-sonnet-5-5";

    /** Consumo en memoria: lo que gastó cada agente y cada tarjeta este mes. */
    static class Store implements AiUsageStore {
        final List<Usage> month = new ArrayList<>();
        final Map<String, List<Usage>> byIssue = new HashMap<>();
        BigDecimal budget;
        BigDecimal cardCap;
        final Map<UUID, BigDecimal> agentBudgets = new LinkedHashMap<>();
        final Set<String> alerts = new HashSet<>();

        void spend(UUID agent, String issue, long input, long output) {
            Usage u = new Usage(agent, MODEL, 1, input, output);
            month.add(u);
            byIssue.computeIfAbsent(issue, k -> new ArrayList<>()).add(u);
        }

        public List<Usage> usage(UUID ws, Instant from, Instant to) { return month; }
        public List<Price> prices() { return List.of(new Price(MODEL, new BigDecimal("2"), new BigDecimal("10"))); }
        public void savePrice(Price price, Instant now) {}
        public void deletePrice(String model) {}
        public Optional<BigDecimal> budget(UUID ws) { return Optional.ofNullable(budget); }
        public void saveBudget(UUID ws, BigDecimal b) { budget = b; }
        public List<Usage> issueUsage(UUID ws, String issueKey) { return byIssue.getOrDefault(issueKey, List.of()); }
        public Optional<BigDecimal> cardCap(UUID ws) { return Optional.ofNullable(cardCap); }
        public void saveCardCap(UUID ws, BigDecimal cap) { cardCap = cap; }
        public Map<UUID, BigDecimal> agentBudgets(UUID ws) { return agentBudgets; }
        public void saveAgentBudget(UUID ws, UUID agentId, BigDecimal monthly) {
            if (monthly == null) agentBudgets.remove(agentId); else agentBudgets.put(agentId, monthly);
        }
        public boolean markAlert(UUID ws, String scope, String month, int level, Instant now) { return alerts.add(scope + "|" + month + "|" + level); }
    }

    Store store;
    AiUsage usage;
    List<AiBudgetAlert> sent;

    @BeforeEach
    void setUp() {
        InMemoryMembers members = new InMemoryMembers();
        members.add(new MemberView(BACKEND, WS, "Agente Backend", true, "BACKEND", "#fff", null));
        members.add(new MemberView(QA, WS, "Agente QA", true, "QA", "#fff", null));
        store = new Store();
        usage = new AiUsage(store, members, Clock.fixed(Instant.parse("2026-11-18T15:00:00Z"), ZoneOffset.UTC), "America/Guayaquil", 0);
        sent = new ArrayList<>();
        usage.setEvents(e -> sent.add((AiBudgetAlert) e));
    }

    @Test
    void anAgentStopsAtItsMonthlyCapAndTheOthersKeepWorking() {
        store.saveAgentBudget(WS, BACKEND, new BigDecimal("5"));
        store.spend(BACKEND, "MED-1", 1_000_000, 300_000); // 2 + 3 = USD 5
        assertThat(usage.blockReason(BACKEND, "MED-2")).hasValueSatisfying(why -> assertThat(why).contains("Agente Backend", "tope mensual", "5.00"));
        assertThat(usage.blockReason(QA, "MED-2")).isEmpty();
    }

    @Test
    void aCardStopsAtItsCapSoLoopsDoNotBurnTheBudget() {
        store.saveCardCap(WS, new BigDecimal("3"));
        store.spend(QA, "MED-7", 500_000, 100_000); // 1 + 1 = USD 2
        assertThat(usage.blockReason(QA, "MED-7")).isEmpty();
        store.spend(BACKEND, "MED-7", 500_000, 100_000); // ya USD 4
        assertThat(usage.blockReason(QA, "MED-7")).hasValueSatisfying(why -> assertThat(why).contains("MED-7", "tope por tarjeta"));
        assertThat(usage.blockReason(QA, "MED-8")).as("otra tarjeta sigue").isEmpty();
    }

    @Test
    void alertsGoAt80AndAt100PercentOncePerMonth() {
        store.saveBudget(WS, new BigDecimal("10"));
        store.spend(BACKEND, "MED-1", 1_000_000, 300_000); // USD 5: 50 %
        usage.checkAlerts();
        assertThat(sent).isEmpty();
        store.spend(QA, "MED-2", 1_000_000, 100_000); // USD 8: 80 %
        usage.checkAlerts();
        usage.checkAlerts();
        assertThat(sent).extracting(AiBudgetAlert::level).as("una sola vez").containsExactly(80);
        store.spend(QA, "MED-3", 1_000_000, 0); // USD 10: el tope
        usage.checkAlerts();
        assertThat(sent).extracting(AiBudgetAlert::level).containsExactly(80, 100);
        assertThat(sent.getLast().agentName()).as("el del espacio").isNull();
        assertThat(usage.blockReason(QA, "MED-4")).hasValueSatisfying(why -> assertThat(why).contains("tope mensual"));
    }

    @Test
    void anAgentCapAlertsWithItsName() {
        store.saveAgentBudget(WS, BACKEND, new BigDecimal("4"));
        store.spend(BACKEND, "MED-1", 1_000_000, 200_000); // USD 4
        usage.checkAlerts();
        assertThat(sent).extracting(AiBudgetAlert::agentName).containsExactly("Agente Backend");
        assertThat(sent.getFirst().level()).as("llegó directo al tope: se avisa el 100 %, no el 80 %").isEqualTo(100);
        assertThat(usage.report().byAgent()).anySatisfy(a -> assertThat(a.budgetUsd()).isEqualByComparingTo("4"));
    }
}
