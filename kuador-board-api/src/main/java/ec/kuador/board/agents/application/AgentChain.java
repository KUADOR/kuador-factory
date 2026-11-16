package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.AgentMode;
import ec.kuador.board.agents.domain.AgentTeamStore;
import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueMoved;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.shared.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cadena automática: cuando un issue se crea o cambia de columna, lo que quedó sin asignar en una etapa que cubre un
 * agente «En el tablero» del equipo se le asigna a ese agente, y el agente arranca solo. Así el trabajo avanza
 * de etapa en etapa sin que una persona mueva y asigne cada tarjeta; se detiene en las puertas de aprobación,
 * en las preguntas y en las etapas que hace una persona. Revisa todo el tablero porque al terminar una tarea
 * pueden destrabarse otras que dependían de ella.
 */
@Component
class AgentChain {

    private static final Logger log = LoggerFactory.getLogger(AgentChain.class);

    private final IssueApi issues;
    private final MemberApi members;
    private final AgentTeamStore team;
    private final CatalogApi catalog;
    private final TransactionTemplate tx;

    AgentChain(IssueApi issues, MemberApi members, AgentTeamStore team, CatalogApi catalog, PlatformTransactionManager txManager) {
        this.issues = issues;
        this.members = members;
        this.team = team;
        this.catalog = catalog;
        this.tx = new TransactionTemplate(txManager);
    }

    @Async
    @TransactionalEventListener
    void on(IssueMoved event) {
        chain(event.workspaceId(), event.projectKey(), event.issueKey(), event.actorId());
    }

    /** Una tarjeta creada directo en una etapa (p. ej. en Refinamiento) también arranca sola. */
    @Async
    @TransactionalEventListener
    void on(ec.kuador.board.issue.IssueCreated event) {
        chain(event.workspaceId(), event.projectKey(), event.issueKey(), event.actorId());
    }

    private void chain(UUID workspaceId, String projectKey, String issueKey, UUID actorId) {
        TenantContext.run(workspaceId, () -> {
            try {
                tx.executeWithoutResult(s -> assignOpenWork(workspaceId, projectKey, actorId));
            } catch (RuntimeException e) {
                log.warn("No se pudo encadenar el trabajo de {} en {}: {}", issueKey, projectKey, e.getMessage());
            }
        });
    }

    void assignOpenWork(UUID workspaceId, String projectKey, UUID actorId) {
        List<MemberView> serverAgents = serverAgents(workspaceId, projectKey);
        if (serverAgents.isEmpty()) return;
        for (IssueApi.QueueItem item : issues.openForAgents(projectKey)) {
            Optional<MemberView> agent = serverAgents.stream().filter(a -> item.agentRoles().contains(a.role())).findFirst();
            if (agent.isEmpty()) continue; // la cubre un agente local: la toma desde /pendientes
            issues.reassign(item.key(), agent.get().id(), actorId);
            log.info("Cadena: {} pasó a {} ({})", item.key(), agent.get().name(), item.statusName());
        }
    }

    /** Agentes del equipo del tablero que trabajan en el servidor, en el orden del equipo. Los que trabajan sobre el código, nunca. */
    private List<MemberView> serverAgents(UUID workspaceId, String projectKey) {
        Map<UUID, AgentTeamStore.Profile> profiles = team.profiles(workspaceId);
        return team.team(workspaceId, projectKey).stream()
                .filter(id -> Optional.ofNullable(profiles.get(id)).map(p -> p.mode() == AgentMode.SERVER).orElse(false))
                .flatMap(id -> members.find(id).stream())
                .filter(MemberView::agent)
                .filter(a -> !catalog.role(a.role()).map(AgentRole::worksOnCode).orElse(false))
                .toList();
    }
}
