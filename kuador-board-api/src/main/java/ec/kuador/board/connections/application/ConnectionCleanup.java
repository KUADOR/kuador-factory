package ec.kuador.board.connections.application;

import ec.kuador.board.connections.domain.ConnectionStore;
import ec.kuador.board.project.ProjectDeleted;
import ec.kuador.board.shared.tenant.TenantAwareListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;

/** Se borró un tablero: deja de usar sus conexiones (las conexiones siguen en el espacio). */
@Component
class ConnectionCleanup extends TenantAwareListener {

    private final ConnectionStore store;

    ConnectionCleanup(ConnectionStore store, PlatformTransactionManager txManager) {
        super(txManager);
        this.store = store;
    }

    @Async
    @TransactionalEventListener
    void on(ProjectDeleted event) {
        inTenantTransaction(event, () -> store.deleteUsesOfProject(event.workspaceId(), event.projectKey()));
    }
}
