package ec.kuador.board.insights.application;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.catalog.ColumnCategory;
import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.insights.application.InsightsData.Card;
import ec.kuador.board.insights.application.InsightsData.Event;
import ec.kuador.board.insights.application.InsightsData.Price;
import ec.kuador.board.insights.application.InsightsData.Run;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.ForbiddenException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los indicadores del dashboard, armados con el historial de cada tarjeta.
 *
 * <p>La historia de una tarjeta se reconstruye con sus movimientos y asignaciones: cada tramo entre dos cambios queda en
 * una columna y con un responsable. Si lo tiene un agente, es tiempo de agente trabajando; si lo tiene una persona, con
 * una persona; si nadie la tiene, o está en una puerta esperando a quien aprueba, esperando. Así se ve dónde se atasca.
 *
 * <p>Las horas ahorradas son prudentes a propósito: solo cuentan tarjetas que llegaron a «Hecho» (aprobadas), con puntos
 * de historia y en las que trabajó un agente. Para cada una, lo que habría tardado una persona (puntos × horas por punto)
 * menos lo que tardaron los agentes, nunca menos de cero.
 */
@Service
public class InsightsService {

    static final BigDecimal DEFAULT_HOURS_PER_POINT = new BigDecimal("4");
    static final int MIN_DAYS = 7;
    static final int MAX_DAYS = 90;
    static final int WEEKS = 8;
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final BigDecimal CACHE_READ = new BigDecimal("0.10");
    private static final BigDecimal CACHE_WRITE = new BigDecimal("1.25");

    // ---- Lo que devuelve ----

    /** Un número con el del período anterior (para la flecha); {@code previous} nulo = sin dato comparable. */
    public record Kpi(Double value, Double previous) {}

    public record Savings(BigDecimal hoursPerPoint, int cards, int points, double estimatedHours, double agentHours, int unestimated) {}

    public record Week(LocalDate start, int withAgents, int withoutAgents) {}

    /** Tiempo en horas por columna: con un agente trabajando, con una persona y esperando (sin nadie o en una puerta). */
    public record StageTime(String column, String name, boolean gate, double agentHours, double personHours, double waitingHours) {}

    public record BoardFlow(String projectKey, String name, List<StageTime> stages, double agentHours, double personHours, double waitingHours) {}

    public record BoardHealth(String key, String name, int open, int inProgress, int waitingDecision, int stuck, int done, double reworkRate) {}

    public record Recent(String key, String title, String projectKey, String projectName, Instant doneAt, String by, boolean byAgent,
            double leadTimeHours, boolean agentsHelped) {}

    public record AgentStat(UUID id, String name, String role, int delivered, int failed, double avgMinutes, BigDecimal costUsd) {}

    public record Company(int days, Instant from, Instant to, Kpi done, Kpi leadTimeHours, Kpi hoursSaved, Kpi costPerCard, Savings savings,
            List<Week> weeks, List<BoardFlow> flow, List<BoardHealth> boards, List<Recent> recent, List<AgentStat> agents, BigDecimal costUsd,
            List<String> unpricedModels) {}

    public record MyCard(String key, String title, String projectKey, String projectName, String column, Instant since) {}

    public record Supervised(String key, String title, String projectKey, String column, String agent, boolean working) {}

    public record Delivery(String key, String title, String projectKey, String agent, Instant at) {}

    public record EpicProgress(String key, String title, String projectKey, int done, int total, int inProgress) {}

    public record MyWeek(int closed, Double leadTimeHours, int reworked, int agentDeliveries) {}

    public record Mine(List<MyCard> assigned, List<Supervised> supervised, List<Delivery> deliveredToday, List<EpicProgress> epics, MyWeek week) {}

    private final InsightsData data;
    private final ProjectApi projects;
    private final MemberApi members;
    private final Clock clock;

    InsightsService(InsightsData data, ProjectApi projects, MemberApi members, Clock clock) {
        this.data = data;
        this.projects = projects;
        this.members = members;
        this.clock = clock;
    }

    // ---- Ajuste ----

    @Transactional(readOnly = true)
    public BigDecimal hoursPerPoint(UUID workspaceId) {
        return data.hoursPerPoint(workspaceId).orElse(DEFAULT_HOURS_PER_POINT);
    }

    @Transactional
    public BigDecimal setHoursPerPoint(AuthenticatedMember who, BigDecimal hours) {
        if (who.agent() || who.runner() || !who.administers()) throw new ForbiddenException("Este ajuste es de quien administra el espacio.");
        if (hours == null || hours.signum() <= 0 || hours.compareTo(new BigDecimal("40")) > 0) {
            throw new DomainException("Escribe cuántas horas vale un punto: más de 0 y hasta 40.");
        }
        BigDecimal clean = hours.setScale(2, RoundingMode.HALF_UP);
        data.saveHoursPerPoint(who.workspaceId(), clean, clock.instant());
        return clean;
    }

    // ---- Cómo va la empresa ----

    @Transactional(readOnly = true)
    public Company company(AuthenticatedMember who, Integer requestedDays) {
        if (who.agent() || who.runner() || !who.administers()) throw new ForbiddenException("El resumen de la empresa lo ve quien administra el espacio.");
        int days = clampDays(requestedDays);
        Instant now = clock.instant();
        Instant from = now.minus(Duration.ofDays(days));
        Instant prevFrom = from.minus(Duration.ofDays(days));
        Instant since = min(prevFrom, now.minus(Duration.ofDays(WEEKS * 7L)));
        Context ctx = new Context(who.workspaceId(), since);
        BigDecimal hpp = hoursPerPoint(who.workspaceId());

        List<Card> done = ctx.doneBetween(from, now);
        List<Card> prevDone = ctx.doneBetween(prevFrom, from);
        Savings savings = savings(ctx, done, hpp);
        Cost cost = cost(ctx, from, now);
        Cost prevCost = cost(ctx, prevFrom, from);

        Kpi doneKpi = new Kpi((double) done.size(), (double) prevDone.size());
        Kpi lead = new Kpi(avgLead(ctx, done), avgLead(ctx, prevDone));
        Kpi saved = new Kpi(round(savedHours(ctx, done, hpp)), round(savedHours(ctx, prevDone, hpp)));
        Kpi perCard = new Kpi(perCard(cost, done.size()), perCard(prevCost, prevDone.size()));

        return new Company(days, from, now, doneKpi, lead, saved, perCard, savings, weeks(ctx, now), flow(ctx, from, now), health(ctx, from, now),
                recent(ctx, from, now), agentStats(ctx, from, now), cost.total() == null ? null : cost.total().setScale(2, RoundingMode.HALF_UP),
                cost.unpriced());
    }

    // ---- Mi trabajo ----

    @Transactional(readOnly = true)
    public Mine mine(AuthenticatedMember who) {
        if (who.agent() || who.runner()) throw new ForbiddenException("«Mi trabajo» es de cada persona.");
        Instant now = clock.instant();
        Context ctx = new Context(who.workspaceId(), now.minus(Duration.ofDays(14)));
        UUID me = who.memberId();

        List<MyCard> assigned = ctx.cards.values().stream()
                .filter(c -> me.equals(c.assigneeId()) && !ctx.isDone(c) && !"EPIC".equals(c.type()))
                .sorted(Comparator.comparing(Card::updatedAt))
                .limit(20)
                .map(c -> new MyCard(c.key(), c.title(), c.projectKey(), ctx.projectName(c.projectKey()), ctx.columnName(c), c.updatedAt()))
                .toList();

        Set<String> running = ctx.runs.stream().filter(r -> "RUNNING".equals(r.status())).map(Run::issueKey).collect(Collectors.toSet());
        List<Supervised> supervised = ctx.cards.values().stream()
                .filter(c -> me.equals(c.supervisorId()) && c.assigneeId() != null && ctx.isAgent(c.assigneeId()) && !ctx.isDone(c))
                .sorted(Comparator.comparing(Card::updatedAt).reversed())
                .limit(20)
                .map(c -> new Supervised(c.key(), c.title(), c.projectKey(), ctx.columnName(c), ctx.name(c.assigneeId()), running.contains(c.key())))
                .toList();

        Instant day = now.minus(Duration.ofDays(1));
        Map<String, Delivery> deliveries = new LinkedHashMap<>();
        ctx.runs.stream()
                .filter(r -> "SUCCEEDED".equals(r.status()) && r.finishedAt() != null && r.finishedAt().isAfter(day))
                .sorted(Comparator.comparing(Run::finishedAt).reversed())
                .forEach(r -> {
                    Card c = ctx.cards.get(r.issueKey());
                    if (c != null && me.equals(c.supervisorId())) deliveries.putIfAbsent(r.issueKey() + r.agentId(),
                            new Delivery(c.key(), c.title(), c.projectKey(), ctx.name(r.agentId()), r.finishedAt()));
                });

        List<EpicProgress> epics = ctx.cards.values().stream()
                .filter(c -> "EPIC".equals(c.type()) && !ctx.isDone(c) && (me.equals(c.supervisorId()) || me.equals(c.reporterId())))
                .map(e -> {
                    List<Card> children = ctx.cards.values().stream().filter(c -> e.key().equals(c.parentKey())).toList();
                    int doneChildren = (int) children.stream().filter(ctx::isDone).count();
                    int moving = (int) children.stream().filter(c -> !ctx.isDone(c) && (c.assigneeId() != null || ctx.category(c) == ColumnCategory.IN_PROGRESS)).count();
                    return new EpicProgress(e.key(), e.title(), e.projectKey(), doneChildren, children.size(), moving);
                })
                .sorted(Comparator.comparing(EpicProgress::key))
                .toList();

        Instant week = now.minus(Duration.ofDays(7));
        List<Card> myDone = ctx.doneBetween(week, now).stream()
                .filter(c -> me.equals(c.supervisorId()) || me.equals(c.assigneeId()) || me.equals(c.reporterId()) || me.equals(ctx.doneBy.get(c.key())))
                .toList();
        int agentDeliveries = (int) ctx.runs.stream()
                .filter(r -> "SUCCEEDED".equals(r.status()) && r.finishedAt() != null && r.finishedAt().isAfter(week))
                .filter(r -> Optional.ofNullable(ctx.cards.get(r.issueKey())).map(c -> me.equals(c.supervisorId())).orElse(false))
                .count();
        MyWeek myWeek = new MyWeek(myDone.size(), avgLead(ctx, myDone), (int) myDone.stream().filter(c -> c.reworkCount() > 0).count(), agentDeliveries);
        return new Mine(assigned, supervised, List.copyOf(deliveries.values()).subList(0, Math.min(10, deliveries.size())), epics, myWeek);
    }

    // ---- Cálculos ----

    private Savings savings(Context ctx, List<Card> done, BigDecimal hpp) {
        int cards = 0;
        int points = 0;
        int unestimated = 0;
        double estimated = 0;
        double agent = 0;
        for (Card c : done) {
            if (!ctx.agentTouched(c.key())) continue;
            if (c.storyPoints() == null || c.storyPoints() <= 0) {
                unestimated++;
                continue;
            }
            cards++;
            points += c.storyPoints();
            estimated += c.storyPoints() * hpp.doubleValue();
            agent += ctx.agentHours(c.key());
        }
        return new Savings(hpp, cards, points, round(estimated), round(agent), unestimated);
    }

    /** Por tarjeta, lo que habría tardado una persona menos lo que tardaron los agentes; nunca menos de cero. */
    private double savedHours(Context ctx, List<Card> done, BigDecimal hpp) {
        double total = 0;
        for (Card c : done) {
            if (!ctx.agentTouched(c.key()) || c.storyPoints() == null || c.storyPoints() <= 0) continue;
            total += Math.max(0, c.storyPoints() * hpp.doubleValue() - ctx.agentHours(c.key()));
        }
        return total;
    }

    private record Cost(BigDecimal total, List<String> unpriced) {}

    private Cost cost(Context ctx, Instant from, Instant to) {
        BigDecimal total = BigDecimal.ZERO;
        Set<String> unpriced = new java.util.TreeSet<>();
        for (Run r : ctx.runs) {
            if (r.createdAt().isBefore(from) || !r.createdAt().isBefore(to)) continue;
            if (r.input() + r.output() + r.cacheRead() + r.cacheWrite() == 0) continue; // corridas locales: usan la suscripción de la persona
            Price p = r.model() == null ? null : ctx.prices.get(r.model());
            if (p == null) {
                if (r.model() != null) unpriced.add(r.model());
                continue;
            }
            BigDecimal input = BigDecimal.valueOf(r.input()).add(BigDecimal.valueOf(r.cacheRead()).multiply(CACHE_READ))
                    .add(BigDecimal.valueOf(r.cacheWrite()).multiply(CACHE_WRITE));
            total = total.add(p.inputPerMTok().multiply(input).add(p.outputPerMTok().multiply(BigDecimal.valueOf(r.output())))
                    .divide(MILLION, 6, RoundingMode.HALF_UP));
        }
        return new Cost(unpriced.isEmpty() ? total : null, List.copyOf(unpriced));
    }

    private static Double perCard(Cost cost, int done) {
        if (cost.total() == null || done == 0) return null;
        return cost.total().divide(BigDecimal.valueOf(done), 2, RoundingMode.HALF_UP).doubleValue();
    }

    private Double avgLead(Context ctx, List<Card> done) {
        if (done.isEmpty()) return null;
        return round(done.stream().mapToDouble(c -> hours(c.createdAt(), ctx.doneAt.get(c.key()))).average().orElse(0));
    }

    private List<Week> weeks(Context ctx, Instant now) {
        LocalDate thisWeek = now.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Week> out = new ArrayList<>();
        for (int i = WEEKS - 1; i >= 0; i--) {
            LocalDate start = thisWeek.minusWeeks(i);
            Instant a = start.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant b = start.plusWeeks(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            List<Card> done = ctx.doneBetween(a, b);
            int agents = (int) done.stream().filter(c -> ctx.agentTouched(c.key())).count();
            out.add(new Week(start, agents, done.size() - agents));
        }
        return out;
    }

    /** Dónde se atasca: el tiempo de cada columna de trabajo repartido en agente, persona y espera. */
    private List<BoardFlow> flow(Context ctx, Instant from, Instant to) {
        List<BoardFlow> out = new ArrayList<>();
        for (ProjectView p : ctx.boards.values()) {
            Map<String, double[]> byColumn = new LinkedHashMap<>();
            for (Card c : ctx.cards.values()) {
                if (!c.projectKey().equals(p.key()) || "EPIC".equals(c.type())) continue;
                for (Segment s : ctx.segments(c, to)) {
                    Instant a = max(s.start(), from);
                    Instant b = min(s.end(), to);
                    if (!a.isBefore(b)) continue;
                    Optional<ColumnDef> col = p.column(s.status());
                    if (col.isEmpty() || col.get().category() == ColumnCategory.DONE) continue;
                    Optional<StageDef> stage = p.stage(s.status());
                    // El backlog sin etapa no es un atasco: es trabajo que todavía no se pidió.
                    if (col.get().category() == ColumnCategory.TODO && stage.map(st -> st.roles().isEmpty() && !st.gate()).orElse(true)) continue;
                    boolean gate = stage.map(StageDef::gate).orElse(false);
                    double h = hours(a, b);
                    double[] acc = byColumn.computeIfAbsent(s.status(), k -> new double[3]);
                    if (s.assignee() != null && ctx.isAgent(s.assignee())) acc[0] += h;
                    else if (s.assignee() != null && !gate) acc[1] += h;
                    else acc[2] += h;
                }
            }
            if (byColumn.isEmpty()) continue;
            List<StageTime> stages = p.columns().stream().filter(col -> byColumn.containsKey(col.key()))
                    .map(col -> {
                        double[] v = byColumn.get(col.key());
                        return new StageTime(col.key(), col.name(), p.stage(col.key()).map(StageDef::gate).orElse(false), round(v[0]), round(v[1]), round(v[2]));
                    })
                    .toList();
            out.add(new BoardFlow(p.key(), p.name(), stages, round(stages.stream().mapToDouble(StageTime::agentHours).sum()),
                    round(stages.stream().mapToDouble(StageTime::personHours).sum()), round(stages.stream().mapToDouble(StageTime::waitingHours).sum())));
        }
        out.sort(Comparator.comparingDouble((BoardFlow f) -> f.agentHours() + f.personHours() + f.waitingHours()).reversed());
        return out;
    }

    private List<BoardHealth> health(Context ctx, Instant from, Instant to) {
        Map<String, Run> lastRun = new HashMap<>();
        ctx.runs.stream().sorted(Comparator.comparing(Run::createdAt)).forEach(r -> lastRun.put(r.issueKey(), r));
        List<BoardHealth> out = new ArrayList<>();
        for (ProjectView p : ctx.boards.values()) {
            List<Card> cards = ctx.cards.values().stream().filter(c -> c.projectKey().equals(p.key()) && !"EPIC".equals(c.type())).toList();
            List<Card> open = cards.stream().filter(c -> !ctx.isDone(c)).toList();
            int inProgress = (int) open.stream().filter(c -> ctx.category(c) == ColumnCategory.IN_PROGRESS).count();
            int gates = (int) open.stream().filter(c -> p.stage(c.status()).map(StageDef::gate).orElse(false)).count();
            int stuck = (int) open.stream().filter(c -> Optional.ofNullable(lastRun.get(c.key()))
                    .map(r -> "FAILED".equals(r.status()) || "SKIPPED".equals(r.status())).orElse(false)).count();
            List<Card> done = cards.stream().filter(c -> inRange(ctx.doneAt.get(c.key()), from, to) && ctx.isDone(c)).toList();
            double rework = done.isEmpty() ? 0 : round((double) done.stream().filter(c -> c.reworkCount() > 0).count() / done.size());
            out.add(new BoardHealth(p.key(), p.name(), open.size(), inProgress, gates, stuck, done.size(), rework));
        }
        return out;
    }

    private List<Recent> recent(Context ctx, Instant from, Instant to) {
        return ctx.doneBetween(from, to).stream()
                .sorted(Comparator.comparing((Card c) -> ctx.doneAt.get(c.key())).reversed())
                .limit(12)
                .map(c -> {
                    UUID by = ctx.doneBy.get(c.key());
                    return new Recent(c.key(), c.title(), c.projectKey(), ctx.projectName(c.projectKey()), ctx.doneAt.get(c.key()),
                            by == null ? null : ctx.name(by), by != null && ctx.isAgent(by), round(hours(c.createdAt(), ctx.doneAt.get(c.key()))),
                            ctx.agentTouched(c.key()));
                })
                .toList();
    }

    private List<AgentStat> agentStats(Context ctx, Instant from, Instant to) {
        Map<UUID, List<Run>> byAgent = ctx.runs.stream().filter(r -> inRange(r.createdAt(), from, to)).collect(Collectors.groupingBy(Run::agentId));
        List<AgentStat> out = new ArrayList<>();
        byAgent.forEach((agent, runs) -> {
            MemberView m = ctx.people.get(agent);
            if (m == null || !m.agent()) return;
            List<Run> ok = runs.stream().filter(r -> "SUCCEEDED".equals(r.status()) && r.finishedAt() != null).toList();
            int failed = (int) runs.stream().filter(r -> "FAILED".equals(r.status())).count();
            double minutes = ok.isEmpty() ? 0 : ok.stream().mapToDouble(r -> hours(r.createdAt(), r.finishedAt()) * 60).average().orElse(0);
            Cost c = cost(new Context(ctx, runs), from, to);
            out.add(new AgentStat(agent, m.name(), m.role(), ok.size(), failed, round(minutes), c.total() == null ? null : c.total().setScale(2, RoundingMode.HALF_UP)));
        });
        out.sort(Comparator.comparingInt(AgentStat::delivered).reversed());
        return out;
    }

    // ---- Datos ----

    private record Segment(String status, UUID assignee, Instant start, Instant end) {}

    /** Todo lo que el cálculo necesita, cargado una vez y limitado a los tableros que ve la persona. */
    private final class Context {
        final Map<String, ProjectView> boards = new LinkedHashMap<>();
        final Map<String, Card> cards = new LinkedHashMap<>();
        final Map<String, List<Event>> events = new HashMap<>();
        final List<Run> runs;
        final Map<String, Price> prices;
        final Map<UUID, MemberView> people = new HashMap<>();
        final Map<String, Instant> doneAt = new HashMap<>();
        final Map<String, UUID> doneBy = new HashMap<>();
        final Set<String> touched = new HashSet<>();
        final Map<String, Double> agentHours = new HashMap<>();
        final Instant since;

        Context(UUID ws, Instant since) {
            this.since = since;
            for (String key : projects.visibleKeys()) projects.find(key).ifPresent(p -> boards.put(p.key(), p));
            members.humans().forEach(m -> people.put(m.id(), m));
            members.agents().forEach(m -> people.put(m.id(), m));
            data.cards(ws, boards.keySet()).forEach(c -> cards.put(c.key(), c));
            data.events(ws, boards.keySet(), since).forEach(e -> events.computeIfAbsent(e.issueKey(), k -> new ArrayList<>()).add(e));
            runs = data.runs(ws, boards.keySet(), since);
            prices = data.prices();
            for (Card c : cards.values()) {
                if (!isDone(c)) continue;
                for (Event e : events.getOrDefault(c.key(), List.of())) {
                    if ("MOVED".equals(e.type()) && doneColumn(c.projectKey(), e.to())) {
                        doneAt.put(c.key(), e.at());
                        doneBy.put(c.key(), e.actorId());
                    }
                }
            }
            for (Run r : runs) {
                if (!"SUCCEEDED".equals(r.status()) || r.finishedAt() == null) continue;
                touched.add(r.issueKey());
                agentHours.merge(r.issueKey(), hours(r.createdAt(), r.finishedAt()), Double::sum);
            }
            events.values().forEach(list -> list.stream()
                    .filter(e -> "ASSIGNED".equals(e.type()) && e.to() != null && isAgent(uuid(e.to())))
                    .forEach(e -> touched.add(e.issueKey())));
        }

        /** Copia con otras corridas (para el costo de un agente). */
        Context(Context base, List<Run> runs) {
            this.since = base.since;
            this.runs = runs;
            this.prices = base.prices;
        }

        boolean doneColumn(String project, String column) {
            ProjectView p = boards.get(project);
            return p != null && column != null && p.column(column).map(c -> c.category() == ColumnCategory.DONE).orElse(false);
        }

        boolean isDone(Card c) {
            return doneColumn(c.projectKey(), c.status());
        }

        ColumnCategory category(Card c) {
            ProjectView p = boards.get(c.projectKey());
            return p == null ? null : p.column(c.status()).map(ColumnDef::category).orElse(null);
        }

        String columnName(Card c) {
            ProjectView p = boards.get(c.projectKey());
            return p == null ? c.status() : p.column(c.status()).map(ColumnDef::name).orElse(c.status());
        }

        String projectName(String key) {
            ProjectView p = boards.get(key);
            return p == null ? key : p.name();
        }

        boolean isAgent(UUID id) {
            return id != null && Optional.ofNullable(people.get(id)).map(MemberView::agent).orElse(false);
        }

        String name(UUID id) {
            return Optional.ofNullable(people.get(id)).map(MemberView::name).orElse("—");
        }

        boolean agentTouched(String key) {
            return touched.contains(key);
        }

        double agentHours(String key) {
            return agentHours.getOrDefault(key, 0.0);
        }

        List<Card> doneBetween(Instant from, Instant to) {
            return cards.values().stream().filter(c -> !"EPIC".equals(c.type()) && isDone(c) && inRange(doneAt.get(c.key()), from, to)).toList();
        }

        /** La historia de la tarjeta en tramos (columna y responsable) desde que hay historial cargado hasta {@code now}. */
        List<Segment> segments(Card c, Instant now) {
            List<Event> list = events.getOrDefault(c.key(), List.of());
            String status = c.status();
            UUID assignee = c.assigneeId();
            for (Event e : list) {
                if ("MOVED".equals(e.type()) || "CREATED".equals(e.type())) {
                    status = "MOVED".equals(e.type()) && e.from() != null ? e.from() : e.to();
                    break;
                }
            }
            for (Event e : list) {
                if ("ASSIGNED".equals(e.type())) {
                    assignee = uuid(e.from());
                    break;
                }
            }
            List<Segment> out = new ArrayList<>();
            Instant t = max(c.createdAt(), since);
            for (Event e : list) {
                if (e.at().isAfter(t)) out.add(new Segment(status, assignee, t, e.at()));
                if (e.at().isAfter(t)) t = e.at();
                switch (e.type()) {
                    case "MOVED", "CREATED" -> status = e.to();
                    case "ASSIGNED" -> assignee = uuid(e.to());
                    default -> { }
                }
            }
            if (now.isAfter(t)) out.add(new Segment(status, assignee, t, now));
            return out;
        }
    }

    // ---- Apoyo ----

    private static int clampDays(Integer days) {
        if (days == null) return 30;
        return Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
    }

    private static boolean inRange(Instant t, Instant from, Instant to) {
        return t != null && !t.isBefore(from) && t.isBefore(to);
    }

    private static double hours(Instant a, Instant b) {
        if (a == null || b == null) return 0;
        return Math.max(0, Duration.between(a, b).toSeconds() / 3600.0);
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static Double round(Double v) {
        return v == null ? null : round(v.doubleValue());
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static UUID uuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
