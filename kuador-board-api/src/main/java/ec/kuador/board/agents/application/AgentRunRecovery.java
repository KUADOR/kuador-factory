package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.AgentRun;
import ec.kuador.board.agents.domain.AgentRunRepository;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueAssigned;
import ec.kuador.board.shared.cluster.ClusterLock;
import ec.kuador.board.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Que un reinicio no deje trabajo tirado. Cada réplica renueva la señal de vida de las ejecuciones que tiene corriendo;
 * la que deja de darla (su réplica se cayó o se reinició) se da por interrumpida y, si la tarjeta sigue en manos de
 * ese agente, se retoma sola una vez. Si se vuelve a interrumpir, queda para que una persona la reasigne.
 */
@Component
class AgentRunRecovery {

    private static final Logger log = LoggerFactory.getLogger(AgentRunRecovery.class);
    /** Sin señal de vida este tiempo = la réplica que la corría ya no está. La señal se renueva cada 20 s. */
    static final Duration SILENT = Duration.ofSeconds(90);
    /** Una segunda interrupción de la misma tarjeta en este tiempo ya no se retoma sola. */
    static final Duration RETAKE_WINDOW = Duration.ofMinutes(30);
    static final String RETAKEN = "La API se reinició mientras el agente trabajaba: lo retoma solo.";
    static final String GIVEN_UP = "La API se reinició mientras el agente trabajaba. Vuelve a asignarle el issue para reintentar.";

    record Silent(UUID id, UUID workspaceId) {}

    private final JdbcClient jdbc;
    private final AgentRunRepository runs;
    private final LiveRuns live;
    private final IssueApi issues;
    private final AgentDispatcher dispatcher;
    private final ClusterLock lock;
    private final TransactionTemplate tx;
    private final Clock clock;

    AgentRunRecovery(JdbcClient jdbc, AgentRunRepository runs, LiveRuns live, IssueApi issues, AgentDispatcher dispatcher, ClusterLock lock,
            PlatformTransactionManager txManager, Clock clock) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.live = live;
        this.issues = issues;
        this.dispatcher = dispatcher;
        this.lock = lock;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** Cada réplica, por las suyas. */
    @Scheduled(fixedDelay = 20_000, initialDelay = 20_000)
    void pulse() {
        Set<UUID> mine = live.ids();
        if (mine.isEmpty()) return;
        try {
            jdbc.sql("update agent_run set heartbeat_at = :now where status = 'RUNNING' and id in (:ids)").param("now", Timestamp.from(clock.instant()))
                    .param("ids", mine).update();
        } catch (RuntimeException e) {
            log.warn("No se pudo renovar la señal de vida de {} ejecución(es): {}", mine.size(), e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    void recoverOnOneReplica() {
        lock.runExclusive("ejecuciones-interrumpidas", Duration.ofMinutes(5), this::recover);
    }

    /** Las que dejaron de dar señal de vida, de todos los espacios. Devuelve cuántas se retomaron. */
    int recover() {
        Instant now = clock.instant();
        // Las que corren en el cliente de IA de una persona siguen vivas en su máquina: no se tocan.
        List<Silent> silent = jdbc.sql("""
                select id, workspace_id from agent_run
                where status = 'RUNNING' and coalesce(heartbeat_at, created_at) < :before
                  and (model is null or (model not like 'local:%' and model <> 'claude-code'))
                """).param("before", Timestamp.from(now.minus(SILENT)))
                .query((rs, i) -> new Silent(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class))).list();
        int retaken = 0;
        for (Silent s : silent) {
            if (live.ids().contains(s.id())) continue; // es de esta réplica y sigue viva: solo se atrasó la señal
            try {
                if (TenantContext.call(s.workspaceId(), () -> recover(s, now))) retaken++;
            } catch (RuntimeException e) {
                log.warn("No se pudo recuperar la ejecución {}: {}", s.id(), e.getMessage());
            }
        }
        return retaken;
    }

    boolean recover(Silent s, Instant now) {
        IssueAssigned again = tx.execute(status -> {
            AgentRun run = runs.findById(s.id()).filter(r -> r.status() == AgentRun.Status.RUNNING).orElse(null);
            if (run == null) return null;
            boolean retake = stillHis(run) && !interruptedBefore(run, now);
            runs.save(run.failed(retake ? RETAKEN : GIVEN_UP, now));
            log.warn("Ejecución {} del agente en {} interrumpida: {}", run.id(), run.issueKey(), retake ? "se retoma" : "queda para reasignar");
            if (!retake) return null;
            return new IssueAssigned(UUID.randomUUID(), s.workspaceId(), run.issueKey(), run.projectKey(), run.requestedBy(), now, run.agentId(),
                    run.agentId());
        });
        if (again == null) return false;
        dispatcher.retry(again);
        return true;
    }

    /** La tarjeta existe y sigue en manos de ese agente (no es un chat ni un borrador, y nadie la movió entre tanto). */
    private boolean stillHis(AgentRun run) {
        return issues.context(run.issueKey()).map(c -> run.agentId().equals(c.assigneeId())).orElse(false);
    }

    private boolean interruptedBefore(AgentRun run, Instant now) {
        return runs.findRecent(run.agentId(), run.issueKey(), 6).stream().anyMatch(r -> !r.id().equals(run.id())
                && r.status() == AgentRun.Status.FAILED && RETAKEN.equals(r.error()) && r.finishedAt() != null
                && r.finishedAt().isAfter(now.minus(RETAKE_WINDOW)));
    }
}
