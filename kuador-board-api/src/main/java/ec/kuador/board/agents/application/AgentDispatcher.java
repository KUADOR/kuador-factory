package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.AgentRun;
import ec.kuador.board.agents.domain.AgentRunRepository;
import ec.kuador.board.issue.IssueAssigned;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Despierta a los agentes. Escucha después del commit, en otro hilo y sin transacción propia: el
 * agente fija el espacio del evento y abre transacciones cortas alrededor de la llamada al modelo.
 * Spring Modulith registra el evento: si la API se cae antes de terminar, se reentrega y el agente lo
 * descarta por su id.
 */
@Component
class AgentDispatcher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentDispatcher.class);

    private final ProductOwnerAgent productOwner;
    private final AgentRunRepository runs;
    private final TransactionTemplate tx;
    private final Clock clock;

    AgentDispatcher(ProductOwnerAgent productOwner, AgentRunRepository runs, PlatformTransactionManager txManager, Clock clock) {
        this.productOwner = productOwner;
        this.runs = runs;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener
    void on(IssueAssigned event) {
        productOwner.onAssigned(event);
    }

    /** Al arrancar, una ejecución en RUNNING quedó a medias por un reinicio (de cualquier espacio). */
    @Override
    public void run(ApplicationArguments args) {
        var now = clock.instant();
        TenantContext.asRoot(() -> tx.execute(s -> {
            // Las ejecuciones en Claude Code siguen vivas en la máquina de su persona: no se tocan.
            runs.findStuck(now).stream().filter(r -> !AgentRun.LOCAL.equals(r.model())).forEach(r -> {
                runs.save(r.failed("La API se reinició mientras el agente trabajaba. Vuelve a asignarle el issue para reintentar.", now));
                log.warn("Ejecución {} del agente en {} marcada como interrumpida", r.id(), r.issueKey());
            });
            return null;
        }));
    }
}
