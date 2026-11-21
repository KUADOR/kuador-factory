package ec.kuador.board.mcp.application;

import ec.kuador.board.agents.AgentWorkApi;
import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueApi.IssueContext;
import ec.kuador.board.issue.IssueApi.QueueItem;
import ec.kuador.board.mcp.application.RunnerStore.Lease;
import ec.kuador.board.mcp.application.RunnerStore.Machine;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.ForbiddenException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.BoardScope;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El reparto de tarjetas entre los runners (las máquinas de las personas con sus agentes locales).
 *
 * <p>Qué puede tomar una máquina: lo que espera a un agente que trabaja «en la PC de una persona», con un rol que la
 * máquina declaró atender. Lo que supervisa alguien lo atiende su máquina si tiene una prendida con ese rol; si no, o si
 * nadie lo supervisa, va a la bolsa común y lo toma la primera máquina libre del equipo (y esa persona pasa a
 * supervisarlo). Dentro de una épica, quien tomó su última tarea tiene unos minutos de preferencia: ya tiene el contexto.
 *
 * <p>Cada tarjeta se toma con un préstamo que la máquina renueva mientras trabaja. Si deja de renovarlo (se apagó, se
 * colgó), vence y la tarjeta vuelve a la cola. Tres cortes en un día y deja de ofrecerse: la mira una persona.
 */
@Service
public class RunnerService {

    private static final Logger log = LoggerFactory.getLogger(RunnerService.class);

    static final Duration LEASE = Duration.ofMinutes(3);
    /** Sin señal en este tiempo, la máquina cuenta como apagada. */
    static final Duration ONLINE = Duration.ofSeconds(90);
    static final Duration EPIC_PREFERENCE = Duration.ofMinutes(5);
    static final int MAX_FAILURES = 3;
    static final List<String> TOOLS = List.of("claude", "codex", "opencode");
    static final List<String> OUTCOMES = List.of("DONE", "FAILED", "TIMEOUT", "RATE_LIMITED", "CANCELLED");
    /** Tope de vida de la credencial de una ejecución; antes la revoca el fin del préstamo. */
    static final Duration RUN_TOKEN_TTL = Duration.ofHours(3);

    public record RoleOption(String key, String name, boolean worksOnCode, boolean local) {}

    public record ProjectOption(String key, String name, String kind) {}

    public record Setup(String person, List<RoleOption> roles, List<ProjectOption> projects, String mcpServer, String mcpUrl) {}

    public record Register(UUID machineId, String name, String os, String tool, List<String> roles, Integer capacity, String version, boolean register) {}

    /** {@code dailyMinutes}: tope de trabajo por día (nulo = sin tope); {@code minutesToday}: lo que lleva hoy. */
    public record MachineView(UUID id, String name, String os, String tool, List<String> roles, int capacity, boolean paused, boolean online,
            String version, Instant lastSeen, UUID memberId, String person, List<String> working, Integer dailyMinutes, long minutesToday) {}

    /** Lo que el runner necesita para lanzar el cliente de IA con esa tarjeta. */
    public record Claim(String issueKey, String projectKey, String projectKind, String title, String role, String roleName, boolean worksOnCode,
            UUID agentId, String agentName, String model, String effort, String prompt, String instructions, List<String> allowedTools,
            String mcpServer, String mcpUrl, long leaseSeconds, String runToken, int reworkCount, String resumePrompt, List<String> repositories) {}

    private final RunnerStore store;
    private final RunnerHub hub;
    private final IssueApi issues;
    private final ProjectApi projects;
    private final MemberApi members;
    private final AgentWorkApi work;
    private final CatalogApi catalog;
    private final AgentKit kit;
    private final ec.kuador.board.auth.RunTokens runTokens;
    private ec.kuador.board.agents.AiJobsApi jobs;

    @org.springframework.beans.factory.annotation.Autowired
    void setJobs(ec.kuador.board.agents.AiJobsApi jobs) {
        this.jobs = jobs;
    }
    private final TransactionTemplate tx;
    private final Clock clock;
    private java.time.ZoneId zone = java.time.ZoneId.of("America/Guayaquil");

    @org.springframework.beans.factory.annotation.Value("${board.billing.zone:America/Guayaquil}")
    void setZone(String zone) {
        this.zone = java.time.ZoneId.of(zone);
    }
    private ec.kuador.board.sync.RepositoriesApi repositories;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRepositories(ec.kuador.board.sync.RepositoriesApi repositories) {
        this.repositories = repositories;
    }

    /**
     * Los repositorios del tablero («owner/repo»): el runner hace su copia de trabajo desde uno que ya tenga (o lo clona
     * una sola vez), en vez de que el agente clone el repo en cada tarjeta.
     */
    private List<String> repositoriesOf(ProjectView project) {
        if (!project.software() || repositories == null) return List.of();
        try {
            return repositories.pieces(project.key()).stream().map(ec.kuador.board.sync.RepositoriesApi.Piece::repository)
                    .filter(r -> r != null && !r.isBlank()).distinct().toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Cuándo vio el reparto por primera vez cada tarjeta libre (para la preferencia de la épica). */
    private final Map<String, Instant> firstSeen = new ConcurrentHashMap<>();

    RunnerService(RunnerStore store, RunnerHub hub, IssueApi issues, ProjectApi projects, MemberApi members, AgentWorkApi work, CatalogApi catalog,
            AgentKit kit, ec.kuador.board.auth.RunTokens runTokens, TransactionTemplate tx, Clock clock) {
        this.store = store;
        this.hub = hub;
        this.issues = issues;
        this.projects = projects;
        this.members = members;
        this.work = work;
        this.catalog = catalog;
        this.kit = kit;
        this.runTokens = runTokens;
        this.tx = tx;
        this.clock = clock;
    }

    // ---- Instalación ----

    /** Lo que el asistente de instalación ofrece: los roles de agentes del espacio y los tableros que ve la persona. */
    public Setup setup(AuthenticatedMember who) {
        requireRunner(who);
        List<MemberView> agents = members.agents();
        List<RoleOption> roles = catalog.roles().stream()
                .filter(r -> agents.stream().anyMatch(a -> a.role().equals(r.key())))
                .map(r -> new RoleOption(r.key(), r.name(), r.worksOnCode(),
                        agents.stream().filter(a -> a.role().equals(r.key())).anyMatch(a -> work.profile(a.id()).local())))
                .toList();
        List<ProjectOption> boards = projects.visibleKeys().stream().flatMap(k -> projects.find(k).stream())
                .map(p -> new ProjectOption(p.key(), p.name(), p.kind().name())).toList();
        AgentKit.Connection c = kit.connection();
        return new Setup(who.name(), roles, boards, c.server(), c.url());
    }

    /**
     * Señal de vida de la máquina (cada ~30 s). Con {@code register} (al instalar o reconfigurar) guarda además lo que
     * declaró; si no, manda lo que diga el tablero (ahí se pueden cambiar roles, capacidad o pausarla).
     */
    public MachineView heartbeat(AuthenticatedMember who, Register r) {
        requireRunner(who);
        if (r.machineId() == null) throw new DomainException("Falta el identificador de la máquina.");
        Instant now = clock.instant();
        Optional<Machine> existing = store.machine(r.machineId());
        if (existing.isPresent() && !existing.get().memberId().equals(who.memberId())) {
            throw new ForbiddenException("Esa máquina está conectada a otra persona.");
        }
        Machine m;
        if (existing.isEmpty() || r.register()) {
            List<String> roles = cleanRoles(r.roles());
            String tool = r.tool() == null ? "claude" : r.tool().strip().toLowerCase(java.util.Locale.ROOT);
            if (!TOOLS.contains(tool)) throw new DomainException("Herramienta no soportada: " + tool + ". Usa claude, codex u opencode.");
            int capacity = r.capacity() == null ? 2 : Math.max(1, Math.min(6, r.capacity()));
            m = new Machine(r.machineId(), who.workspaceId(), who.memberId(), clip(r.name(), 80, "Mi máquina"), clip(r.os(), 20, "otro"), tool, roles,
                    capacity, existing.map(Machine::paused).orElse(false), clip(r.version(), 20, null), now, existing.map(Machine::createdAt).orElse(now));
        } else {
            Machine e = existing.get();
            m = new Machine(e.id(), e.workspaceId(), e.memberId(), e.name(), e.os(), e.tool(), e.roles(), e.capacity(), e.paused(),
                    clip(r.version(), 20, e.version()), now, e.createdAt());
        }
        store.saveMachine(m);
        return view(m, store.activeLeases(who.workspaceId()), now);
    }

    // ---- Reparto ----

    /** La siguiente tarjeta para esta máquina, ya tomada a su nombre; vacío si no hay nada para ella. */
    public Optional<Claim> claim(AuthenticatedMember who, UUID machineId) {
        requireRunner(who);
        Machine machine = ownMachine(who, machineId);
        Instant now = clock.instant();
        if (machine.paused()) return Optional.empty();
        // Con la suscripción de una persona el freno es de tiempo: llegado el tope del día, no toma más hasta mañana.
        if (machine.dailyMinutes() != null && store.minutesSince(machineId, startOfDay(now), now) >= machine.dailyMinutes()) return Optional.empty();
        UUID ws = who.workspaceId();
        List<Lease> active = store.activeLeases(ws);
        if (active.stream().filter(l -> l.machineId().equals(machineId)).count() >= machine.capacity()) return Optional.empty();
        Set<String> leased = new java.util.HashSet<>(active.stream().map(Lease::issueKey).toList());
        List<Machine> online = store.machines(ws).stream().filter(x -> !x.paused() && x.lastSeen().isAfter(now.minus(ONLINE))).toList();

        for (Candidate c : candidates(who.memberId())) {
            if (leased.contains(c.item().key())) continue;
            String role = c.item().agentRoles().stream().filter(machine.roles()::contains).findFirst().orElse(null);
            if (role == null) continue;
            MemberView agent = agentFor(c.item(), role).orElse(null);
            if (agent == null || !work.profile(agent.id()).local()) continue; // lo trabaja el tablero (modo servidor)
            if (store.failures(ws, c.item().key(), now.minus(Duration.ofDays(1))) >= MAX_FAILURES) continue;
            Optional<UUID> supervisor = issues.supervisorOf(c.item().key());
            // Lo que supervisa otra persona lo atiende su máquina, si tiene una prendida con ese rol.
            if (supervisor.isPresent() && !supervisor.get().equals(who.memberId()) && serves(online, active, supervisor.get(), role)) continue;
            IssueContext context = issues.context(c.item().key()).orElse(null);
            if (context == null) continue;
            if (preferOther(ws, context, who.memberId(), role, online, active, now)) continue;
            Optional<Claim> claim = tryClaim(who, machine, c.item(), context, role, agent, supervisor.orElse(null), now);
            if (claim.isPresent()) {
                firstSeen.remove(c.item().key());
                return claim;
            }
        }
        return Optional.empty();
    }

    private record Candidate(QueueItem item, boolean mine) {}

    /** Lo que supervisa la persona primero; después lo que espera a agentes en los tableros que ve. */
    private List<Candidate> candidates(UUID person) {
        Map<String, Candidate> all = new LinkedHashMap<>();
        issues.agentQueue(person).stream().filter(i -> BoardScope.allows(i.projectKey())).forEach(i -> all.put(i.key(), new Candidate(i, true)));
        for (String key : projects.visibleKeys()) {
            issues.waitingForAgents(key).forEach(i -> all.putIfAbsent(i.key(), new Candidate(i, false)));
        }
        Instant now = clock.instant();
        all.keySet().forEach(k -> firstSeen.putIfAbsent(k, now));
        return all.values().stream().sorted(Comparator.comparing((Candidate c) -> !c.mine())).toList();
    }

    /** La persona tiene una máquina prendida, sin pausa, que atiende ese rol. */
    private static boolean serves(List<Machine> online, List<Lease> active, UUID person, String role) {
        return online.stream().anyMatch(m -> m.memberId().equals(person) && m.roles().contains(role));
    }

    /** Preferencia de la épica: si otra persona tomó su última tarea hace poco y tiene lugar, se la deja unos minutos. */
    private boolean preferOther(UUID ws, IssueContext context, UUID me, String role, List<Machine> online, List<Lease> active, Instant now) {
        if (context.parentKey() == null) return false;
        Instant seen = firstSeen.getOrDefault(context.key(), now);
        if (seen.isBefore(now.minus(EPIC_PREFERENCE))) return false;
        Optional<UUID> last = store.lastEpicPerson(ws, context.parentKey(), now.minus(Duration.ofDays(2)));
        if (last.isEmpty() || last.get().equals(me)) return false;
        return online.stream().filter(m -> m.memberId().equals(last.get()) && m.roles().contains(role))
                .anyMatch(m -> active.stream().filter(l -> l.machineId().equals(m.id())).count() < m.capacity());
    }

    /** El agente que hace el trabajo: el asignado o, si está por tomar, uno con ese rol (del equipo del tablero primero). */
    private Optional<MemberView> agentFor(QueueItem item, String role) {
        if (item.assigneeId() != null) return members.find(item.assigneeId()).filter(MemberView::agent);
        List<MemberView> withRole = members.agents().stream().filter(a -> a.role().equals(role)).toList();
        return withRole.stream().filter(a -> projects.agentBoardsOf(a.id()).contains(item.projectKey())).findFirst()
                .or(() -> withRole.stream().findFirst());
    }

    private Optional<Claim> tryClaim(AuthenticatedMember who, Machine machine, QueueItem item, IssueContext context, String role, MemberView agent,
            UUID supervisor, Instant now) {
        try {
            return tx.execute(s -> {
                Lease lease = new Lease(UUID.randomUUID(), who.workspaceId(), item.key(), context.parentKey(), item.status(), machine.id(),
                        who.memberId(), agent.id(), now, now.plus(LEASE), null, null, null);
                if (!store.insertLease(lease)) return Optional.<Claim>empty(); // otra máquina la ganó
                if (!who.memberId().equals(supervisor)) issues.handOver(item.key(), who.memberId());
                if (item.assigneeId() == null) issues.takeAs(item.key(), agent.id());
                work.started(agent.id(), item.key(), item.projectKey(), who.memberId(), "runner · " + machine.name());
                // La credencial de esta ejecución: solo esta tarjeta, solo este rol, solo mientras dure el préstamo.
                ec.kuador.board.auth.RunTokens.Issued run = runTokens.issue(who.workspaceId(), who.memberId(),
                        new ec.kuador.board.auth.RunScope(item.key(), role), RUN_TOKEN_TTL);
                store.setRunToken(lease.id(), run.id());
                return Optional.of(claimFor(item, role, agent, run.secret()));
            });
        } catch (RuntimeException e) {
            // Cambió mientras tanto (alguien la tomó, se movió): se sigue con la siguiente.
            log.debug("No se pudo tomar {} para {}: {}", item.key(), machine.name(), e.getMessage());
            return Optional.empty();
        }
    }

    private Claim claimFor(QueueItem item, String role, MemberView agent, String runToken) {
        AgentRole r = catalog.role(role).orElseThrow();
        AgentKit.RunPlan plan = kit.runPlan(role);
        AgentWorkApi.LocalProfile profile = work.profile(agent.id());
        ProjectView project = projects.find(item.projectKey()).orElseThrow();
        String prompt = """
                Trabaja el issue %1$s («%2$s») del tablero %3$s como el agente «%4$s» (usa `agent: %5$s` en todas las herramientas del tablero).
                Ya está tomado a nombre de %6$s: empieza por `role_manual` y `get_issue`, haz el trabajo de tu etapa y termina con `deliver`, `send_back` o `ask_human`.
                Si la tarjeta o su épica tienen adjuntos, léelos con `read_attachment` antes de trabajar: son parte del pedido.
                Trabaja solo este issue y responde al final con un resumen de una o dos líneas."""
                .formatted(item.key(), item.title(), project.name(), r.name(), r.key(), agent.name());
        // Si la máquina ya trabajó esta tarjeta con este rol, retoma esa conversación (ya tiene el contexto) y solo mira
        // qué cambió: el ahorro grande de tokens está en no releer todo en cada vuelta.
        String resume = """
                Volviste a %1$s («%2$s»), que ahora está en «%3$s». Ya conoces la tarjeta: no releas todo. Llama a `get_issue` y mira solo lo nuevo desde tu                 última entrega (comentarios, el motivo de la devolución, documentos cambiados, adjuntos nuevos) y corrige eso. Termina con `deliver`, `send_back` o `ask_human`                 (usa `agent: %4$s`) y responde con un resumen de una o dos líneas."""
                .formatted(item.key(), item.title(), item.statusName(), r.key());
        AgentKit.Connection c = kit.connection();
        return new Claim(item.key(), item.projectKey(), project.kind().name(), item.title(), r.key(), r.name(), r.worksOnCode(), agent.id(), agent.name(),
                profile.model(), profile.effort(), prompt, plan.instructions(), plan.allowedTools(), c.server(), c.url(), LEASE.toSeconds(), runToken,
                item.reworkCount(), resume, repositoriesOf(project));
    }

    // ---- Trabajos de IA de la persona (sin tarjeta) ----

    public Optional<ec.kuador.board.agents.AiJobsApi.JobTask> claimJob(AuthenticatedMember who, UUID machineId) {
        requireRunner(who);
        Machine m = ownMachine(who, machineId);
        if (m.paused()) return Optional.empty();
        return jobs.claim(who.workspaceId(), who.memberId(), machineId);
    }

    public void completeJob(AuthenticatedMember who, UUID machineId, UUID jobId, String text, String error) {
        requireRunner(who);
        ownMachine(who, machineId);
        jobs.complete(who.workspaceId(), who.memberId(), jobId, text, error);
    }

    // ---- Préstamos ----

    /** La máquina sigue trabajando la tarjeta; falso si ya no es suya (debe cortar el cliente de IA). */
    public boolean renew(AuthenticatedMember who, UUID machineId, String issueKey) {
        requireRunner(who);
        ownMachine(who, machineId);
        return store.renew(who.workspaceId(), issueKey.toUpperCase(java.util.Locale.ROOT), machineId, clock.instant().plus(LEASE));
    }

    /**
     * La máquina terminó con la tarjeta. Si el cliente de IA salió bien pero la tarjeta no se movió ni cambió de manos, no
     * avanzó: cuenta como corte. Un límite de la suscripción no cuenta: la retoma la misma u otra máquina.
     */
    public void release(AuthenticatedMember who, UUID machineId, String issueKey, String outcome, String message) {
        requireRunner(who);
        ownMachine(who, machineId);
        String result = outcome == null ? "DONE" : outcome.strip().toUpperCase(java.util.Locale.ROOT);
        if (!OUTCOMES.contains(result)) throw new DomainException("Resultado desconocido: " + outcome + ".");
        String key = issueKey.toUpperCase(java.util.Locale.ROOT);
        UUID ws = who.workspaceId();
        Lease lease = store.activeLease(ws, key).filter(l -> l.machineId().equals(machineId)).orElse(null);
        if (lease == null) return;
        if (result.equals("DONE")) {
            boolean stalled = issues.context(key).map(c -> c.status().equals(lease.status()) && lease.agentId().equals(c.assigneeId())).orElse(false);
            if (stalled) result = "STALLED";
        }
        final String finalResult = result;
        tx.executeWithoutResult(s -> {
            store.release(ws, key, machineId, finalResult, clock.instant());
            runTokens.revoke(lease.runTokenId());
            switch (finalResult) {
                case "DONE" -> { /* el agente ya cerró su ejecución al entregar */ }
                case "STALLED" -> work.failed(lease.agentId(), key, "El cliente de IA terminó sin entregar, devolver ni consultar.");
                case "RATE_LIMITED" -> work.failed(lease.agentId(), key, "Se pausó: la suscripción de IA llegó a su límite de uso.");
                default -> work.failed(lease.agentId(), key, message == null || message.isBlank() ? "Se cortó en la máquina." : message);
            }
            afterFailure(ws, lease, finalResult);
        });
        hub.signal(ws);
    }

    /** Los préstamos que nadie renovó: la máquina se apagó o se colgó. La tarjeta vuelve a la cola. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void expire() {
        Instant now = clock.instant();
        for (Lease lease : store.expired(now)) {
            try {
                TenantContext.run(lease.workspaceId(), () -> tx.executeWithoutResult(s -> {
                    String machine = store.machine(lease.machineId()).map(Machine::name).orElse("la máquina");
                    if (store.release(lease.workspaceId(), lease.issueKey(), lease.machineId(), "EXPIRED", now).isEmpty()) return;
                    runTokens.revoke(lease.runTokenId());
                    work.failed(lease.agentId(), lease.issueKey(), "Se cortó: «" + machine + "» dejó de responder.");
                    afterFailure(lease.workspaceId(), lease, "EXPIRED");
                }));
                hub.signal(lease.workspaceId());
            } catch (RuntimeException e) {
                log.warn("No se pudo liberar el préstamo de {}: {}", lease.issueKey(), e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    void cleanup() {
        store.deleteLeasesBefore(clock.instant().minus(Duration.ofDays(7)));
        Instant old = clock.instant().minus(Duration.ofHours(6));
        firstSeen.values().removeIf(t -> t.isBefore(old));
    }

    /** Al tercer corte en un día se deja de ofrecer y se avisa en la tarjeta: la mira una persona. */
    private void afterFailure(UUID ws, Lease lease, String outcome) {
        if (List.of("DONE", "RATE_LIMITED", "CANCELLED").contains(outcome)) return;
        if (store.failures(ws, lease.issueKey(), clock.instant().minus(Duration.ofDays(1))) == MAX_FAILURES) {
            issues.addComment(lease.issueKey(), lease.agentId(), "⚠️ Lo intenté " + MAX_FAILURES
                    + " veces en las máquinas del equipo y no logré terminarlo. Lo dejo para que lo revise una persona: los runners no lo vuelven a tomar por hoy.");
        }
    }

    // ---- Máquinas (para las personas, en el tablero) ----

    /** Las máquinas del espacio: cada persona ve las suyas; quien administra, todas. */
    public List<MachineView> machines(AuthenticatedMember who) {
        Instant now = clock.instant();
        List<Lease> active = store.activeLeases(who.workspaceId());
        return store.machines(who.workspaceId()).stream()
                .filter(m -> who.administers() || m.memberId().equals(who.memberId()))
                .map(m -> view(m, active, now)).toList();
    }

    public MachineView update(AuthenticatedMember who, UUID machineId, Boolean paused, List<String> roles, Integer capacity, Integer dailyMinutes,
            boolean clearDailyMinutes) {
        Machine m = manageable(who, machineId);
        Machine next = new Machine(m.id(), m.workspaceId(), m.memberId(), m.name(), m.os(), m.tool(), roles == null ? m.roles() : cleanRoles(roles),
                capacity == null ? m.capacity() : Math.max(1, Math.min(6, capacity)), paused == null ? m.paused() : paused, m.version(), m.lastSeen(),
                m.createdAt(), m.dailyMinutes());
        store.saveMachine(next);
        if (clearDailyMinutes || dailyMinutes != null) {
            if (dailyMinutes != null && (dailyMinutes < 15 || dailyMinutes > 1440)) throw new DomainException("El tope por día va de 15 minutos a 24 horas.");
            store.setDailyMinutes(machineId, clearDailyMinutes ? null : dailyMinutes);
        }
        if (Boolean.FALSE.equals(paused)) hub.signal(who.workspaceId());
        return view(store.machine(machineId).orElse(next), store.activeLeases(who.workspaceId()), clock.instant());
    }

    public void delete(AuthenticatedMember who, UUID machineId) {
        manageable(who, machineId);
        store.deleteMachine(machineId);
    }

    // ---- Apoyo ----

    private MachineView view(Machine m, List<Lease> active, Instant now) {
        String person = members.find(m.memberId()).map(MemberView::name).orElse("—");
        List<String> working = active.stream().filter(l -> l.machineId().equals(m.id())).map(Lease::issueKey).toList();
        return new MachineView(m.id(), m.name(), m.os(), m.tool(), m.roles(), m.capacity(), m.paused(), m.lastSeen().isAfter(now.minus(ONLINE)),
                m.version(), m.lastSeen(), m.memberId(), person, working, m.dailyMinutes(), store.minutesSince(m.id(), startOfDay(now), now));
    }

    private Instant startOfDay(Instant now) {
        return now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
    }

    private Machine ownMachine(AuthenticatedMember who, UUID machineId) {
        return store.machine(machineId).filter(m -> m.memberId().equals(who.memberId()) && m.workspaceId().equals(who.workspaceId()))
                .orElseThrow(() -> new NotFoundException("Esta máquina no está registrada: vuelve a ejecutar la instalación."));
    }

    private Machine manageable(AuthenticatedMember who, UUID machineId) {
        if (who.agent() || who.runner()) throw new ForbiddenException("Las máquinas las administra una persona desde el tablero.");
        return store.machine(machineId).filter(m -> m.workspaceId().equals(who.workspaceId()))
                .filter(m -> m.memberId().equals(who.memberId()) || who.administers())
                .orElseThrow(() -> new NotFoundException("No existe la máquina " + machineId + "."));
    }

    private List<String> cleanRoles(List<String> roles) {
        Set<String> known = new java.util.HashSet<>(catalog.roles().stream().map(AgentRole::key).toList());
        List<String> clean = new ArrayList<>();
        for (String r : roles == null ? List.<String>of() : roles) {
            String key = r == null ? "" : r.strip().toUpperCase(java.util.Locale.ROOT);
            if (!known.contains(key)) throw new DomainException("No existe el rol " + r + ".");
            if (!clean.contains(key)) clean.add(key);
        }
        if (clean.isEmpty()) throw new DomainException("Elige al menos un rol para que atienda esta máquina.");
        return clean;
    }

    private static void requireRunner(AuthenticatedMember who) {
        if (!who.runner()) throw new ForbiddenException("Solo un runner conectado usa esta ruta.");
    }

    private static String clip(String value, int max, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String v = value.strip();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
