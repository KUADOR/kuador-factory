package ec.kuador.board.agents.application;

import ec.kuador.board.agents.AgentRunChanged;
import ec.kuador.board.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Cuando un agente termina un trabajo, mira si el gasto cruzó el 80 % o el 100 % de algún tope del mes. */
@Component
class BudgetWatch {

    private static final Logger log = LoggerFactory.getLogger(BudgetWatch.class);

    private final AiUsage usage;

    BudgetWatch(AiUsage usage) {
        this.usage = usage;
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(AgentRunChanged e) {
        if (!"SUCCEEDED".equals(e.status()) && !"FAILED".equals(e.status())) return;
        try {
            TenantContext.run(e.workspaceId(), usage::checkAlerts);
        } catch (RuntimeException ex) {
            log.warn("No se pudo revisar el presupuesto de IA: {}", ex.getMessage());
        }
    }
}
