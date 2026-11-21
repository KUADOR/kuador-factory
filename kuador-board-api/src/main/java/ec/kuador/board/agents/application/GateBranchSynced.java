package ec.kuador.board.agents.application;

import ec.kuador.board.issue.BranchSynced;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * El runner actualizó la rama de una tarjeta que espera en una puerta que revisa un agente: la tarjeta vuelve a esperar
 * el CI del merge y, cuando termina, {@link GateCiWatcher} despierta al revisor (como si se la asignaran de nuevo).
 */
@Component
class GateBranchSynced {

    private final IssueApi issues;
    private final GateCiWaits waits;
    private final Clock clock;

    GateBranchSynced(IssueApi issues, GateCiWaits waits, Clock clock) {
        this.issues = issues;
        this.waits = waits;
        this.clock = clock;
    }

    @TransactionalEventListener(fallbackExecution = true)
    void on(BranchSynced e) {
        TenantContext.run(e.workspaceId(), () -> issues.context(e.issueKey())
                .filter(c -> c.assigneeId() != null && issues.gateReview(e.issueKey(), c.assigneeId()).isPresent())
                .ifPresent(c -> waits.wait(e.issueKey(), e.projectKey(), c.assigneeId(), null, clock.instant())));
    }
}
