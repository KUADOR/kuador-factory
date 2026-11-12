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
    private final BoardAgent boardAgent;
    private final ec.kuador.board.member.MemberApi members;
    private final ec.kuador.board.catalog.CatalogApi catalog;
    private final AgentRunRepository runs;
    private final TransactionTemplate tx;
    private final Clock clock;

    AgentDispatcher(ProductOwnerAgent productOwner, BoardAgent boardAgent, ec.kuador.board.member.MemberApi members,
            ec.kuador.board.catalog.CatalogApi catalog, AgentRunRepository runs, PlatformTransactionManager txManager, Clock clock) {
        this.productOwner = productOwner;
        this.boardAgent = boardAgent;
        this.members = members;
        this.catalog = catalog;
        this.runs = runs;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @Async
    @TransactionalEventListener
    void on(IssueAssigned event) {
        // Un solo agente atiende cada asignación: el de refinamiento (rol con REFINE_ISSUE) o el del tablero.
        if (event.toAssigneeId() != null && refines(event)) productOwner.onAssigned(event);
        else boardAgent.onAssigned(event);
    }

    private boolean refines(IssueAssigned event) {
        return Boolean.TRUE.equals(TenantContext.call(event.workspaceId(), () -> tx.execute(s -> members.find(event.toAssigneeId())
                .filter(ec.kuador.board.member.MemberView::agent)
                .flatMap(m -> catalog.role(m.role()))
                .map(r -> r.automation() == ec.kuador.board.catalog.AgentRole.Automation.REFINE_ISSUE)
                .orElse(false))));
    }

    /** Al arrancar, una ejecución en RUNNING quedó a medias por un reinicio (de cualquier espacio). */
    @Override
    public void run(ApplicationArguments args) {
        var now = clock.instant();
        TenantContext.asRoot(() -> tx.execute(s -> {
            // Las ejecuciones en el cliente de IA de una persona siguen vivas en la máquina de su persona: no se tocan.
            runs.findStuck(now).stream().filter(r -> !r.isLocal()).forEach(r -> {
                runs.save(r.failed("La API se reinició mientras el agente trabajaba. Vuelve a asignarle el issue para reintentar.", now));
                log.warn("Ejecución {} del agente en {} marcada como interrumpida", r.id(), r.issueKey());
            });
            return null;
        }));
    }
}
