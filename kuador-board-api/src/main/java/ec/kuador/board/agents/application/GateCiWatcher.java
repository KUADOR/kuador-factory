package ec.kuador.board.agents.application;

import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueAssigned;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.sync.CodeReviewApi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Vuelve a mirar el CI de las tarjetas que esperan en una puerta (ver {@link GateCi}). Mientras corre, no hace nada; cuando
 * termina (o se acabó la espera), despierta al revisor como si se la asignaran de nuevo, y es el revisor quien decide:
 * revisar, devolver sin gastar tokens o dejarla a una persona. La tarjeta que salió de la puerta o cambió de manos deja
 * de esperar. Sin webhook de GitHub, se pregunta cada pocos minutos.
 */
@Component
class GateCiWatcher {

    private static final Logger log = LoggerFactory.getLogger(GateCiWatcher.class);
    static final Duration EVERY = Duration.ofMinutes(2);

    private final GateCiWaits waits;
    private final ec.kuador.board.shared.cluster.ClusterLock lock;
    private final CodeReviewApi code;
    private final IssueApi issues;
    private final AgentDispatcher dispatcher;
    private final TransactionTemplate tx;
    private final Clock clock;

    GateCiWatcher(GateCiWaits waits, CodeReviewApi code, IssueApi issues, AgentDispatcher dispatcher, PlatformTransactionManager txManager, Clock clock, ec.kuador.board.shared.cluster.ClusterLock lock) {
        this.lock = lock;
        this.waits = waits;
        this.code = code;
        this.issues = issues;
        this.dispatcher = dispatcher;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    void pollOnOneReplica() {
        lock.runExclusive("ci-de-la-puerta", java.time.Duration.ofMinutes(10), this::poll);
    }

    void poll() {
        Instant now = clock.instant();
        for (GateCiWaits.Wait w : TenantContext.asRoot(() -> waits.due(now.minus(EVERY), 50))) {
            try {
                TenantContext.run(w.workspaceId(), () -> check(w, now));
            } catch (RuntimeException e) {
                log.warn("No se pudo mirar el CI de {}: {}", w.issueKey(), e.getMessage());
                TenantContext.run(w.workspaceId(), () -> waits.touch(w.issueKey(), now));
            }
        }
    }

    void check(GateCiWaits.Wait w, Instant now) {
        boolean stillHis = Boolean.TRUE.equals(tx.execute(s -> issues.context(w.issueKey())
                .map(c -> w.agentId().equals(c.assigneeId()) && issues.gateReview(w.issueKey(), w.agentId()).isPresent())
                .orElse(false)));
        if (!stillHis) {
            waits.done(w.issueKey());
            return;
        }
        GateCi.State state = GateCi.of(code, w.projectKey(), w.issueKey());
        if (GateCi.decide(state, w.since(), now) == GateCi.Decision.WAIT) {
            waits.touch(w.issueKey(), now);
            return;
        }
        // El revisor decide y borra la espera; mientras tanto no se vuelve a despertar.
        waits.touch(w.issueKey(), now);
        log.info("CI de {} terminado ({}): despierto al revisor.", w.issueKey(), state.status());
        dispatcher.retry(new IssueAssigned(UUID.randomUUID(), w.workspaceId(), w.issueKey(), w.projectKey(), w.requesterId(), now, w.agentId(),
                w.agentId()));
    }
}
