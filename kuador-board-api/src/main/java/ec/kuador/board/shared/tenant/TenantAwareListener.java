package ec.kuador.board.shared.tenant;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Base para listeners asíncronos de eventos: fija el espacio del evento ANTES de abrir la
 * transacción (Hibernate resuelve el espacio al abrir la sesión). Usar en lugar de
 * {@code @ApplicationModuleListener}, que abre la transacción antes de que el cuerpo pueda fijarlo.
 */
public abstract class TenantAwareListener {

    private final TransactionTemplate tx;

    protected TenantAwareListener(PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
    }

    protected void inTenantTransaction(WorkspaceScoped event, Runnable work) {
        TenantContext.run(event.workspaceId(), () -> tx.executeWithoutResult(status -> work.run()));
    }
}
