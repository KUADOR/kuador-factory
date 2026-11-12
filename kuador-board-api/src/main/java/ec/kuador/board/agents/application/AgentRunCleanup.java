package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.AgentRunRepository;
import ec.kuador.board.project.ProjectDeleted;
import ec.kuador.board.shared.tenant.TenantAwareListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;

/** Se borró un tablero: sus corridas de agentes también. */
@Component
class AgentRunCleanup extends TenantAwareListener {

    private final AgentRunRepository runs;

    AgentRunCleanup(AgentRunRepository runs, PlatformTransactionManager txManager) {
        super(txManager);
        this.runs = runs;
    }

    @Async
    @TransactionalEventListener
    void on(ProjectDeleted event) {
        inTenantTransaction(event, () -> runs.deleteByProject(event.workspaceId(), event.projectKey()));
    }
}
