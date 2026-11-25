package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.AgentMode;
import ec.kuador.board.agents.domain.AgentModelException;
import ec.kuador.board.agents.domain.AgentRun;
import ec.kuador.board.agents.domain.AgentRunRepository;
import ec.kuador.board.agents.domain.AgentTeamStore;
import ec.kuador.board.agents.domain.BoardAgentModel;
import ec.kuador.board.attachment.AttachmentApi;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueAssigned;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.sync.CodeReviewApi;
import ec.kuador.board.workspace.WorkspaceApi;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Un agente que trabaja en el tablero (modo SERVER) con la clave de IA de la empresa. Cuando una persona le asigna
 * una tarjeta: arma el pedido (su manual, las reglas del tablero y la tarjeta con sus comentarios), le pasa los
 * adjuntos, trabaja (puede ejecutar código para calcular, graficar y generar archivos), adjunta lo que generó,
 * comenta un resumen y devuelve la tarjeta a quien se la asignó. Si falta algo, lo pregunta en la tarjeta.
 *
 * <p>La llamada al modelo va fuera de cualquier transacción. Si falla, la ejecución queda FAILED y la tarjeta vuelve
 * a la persona con el motivo; se reintenta asignándosela otra vez.
 */
@Service
public class BoardAgent {

    private static final Logger log = LoggerFactory.getLogger(BoardAgent.class);
    /** Herramienta del rol (Catálogo → Roles) para ejecutar código en el tablero: cálculos, gráficos, PDF, Excel. */
    public static final String CODE_EXECUTION = "CodeExecution";

    private final AgentRunRepository runs;
    private final AiProviders providers;
    private final BoardTeam team;
    private final WorkspaceAi ai;
    private final IssueApi issues;
    private final MemberApi members;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private final AgentBrief brief;
    private final AgentDocuments documents;
    private final GateReviewer gates;
    private final AgentDelivery delivery;

    BoardAgent(AgentRunRepository runs, AiProviders providers, BoardTeam team, WorkspaceAi ai, IssueApi issues, ProjectApi projects, MemberApi members,
            CatalogApi catalog, AttachmentApi attachments, WorkspaceApi workspace, PlatformTransactionManager txManager, Clock clock,
            io.micrometer.core.instrument.MeterRegistry metrics, CodeReviewApi code, ec.kuador.board.sync.RepositoriesApi repositories,
            org.springframework.context.ApplicationEventPublisher events, GateCiWaits ciWaits, ec.kuador.board.issue.BranchSyncApi syncs) {
        this.runs = runs;
        this.providers = providers;
        this.team = team;
        this.ai = ai;
        this.issues = issues;
        this.members = members;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.metrics = metrics;
        StageHandoff handoff = new StageHandoff(issues, projects);
        // El paso que eligió el agente para la tarjeta que trabaja (con choose_next_step), hasta que entrega.
        Map<String, StageHandoff.Next> chosenNext = new java.util.concurrent.ConcurrentHashMap<>();
        AgentTools agentTools = new AgentTools(code, issues, tx);
        this.documents = new AgentDocuments(catalog, tx);
        this.brief = new AgentBrief(issues, projects, members, catalog, attachments, workspace, team, repositories, handoff, chosenNext, agentTools,
                documents, tx);
        this.gates = new GateReviewer(issues, code, agentTools, ciWaits, syncs, events, tx, clock);
        this.delivery = new AgentDelivery(issues, projects, catalog, attachments, handoff, chosenNext, tx);
    }

    /** Conexiones del negocio (correo, agenda, ERP…): opcionales, para no atar las pruebas del agente a ellas. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setConnections(ec.kuador.board.connections.ConnectionsApi connections) {
        brief.use(connections);
    }

    /** Documentos del tablero (plan, diseño, pantallas, identidad): opcionales, para no atar las pruebas del agente a ellos. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDocs(ec.kuador.board.docs.DocsApi docs) {
        documents.use(docs);
    }

    /** Punto de entrada: una tarjeta se asignó a alguien. Trabaja dentro del espacio del evento. */
    public void onAssigned(IssueAssigned event) {
        TenantContext.run(event.workspaceId(), () -> handle(event));
    }

    private void handle(IssueAssigned event) {
        if (event.toAssigneeId() == null) return;
        Optional<MemberView> agent = tx.execute(s -> members.find(event.toAssigneeId())).filter(MemberView::agent);
        // Un agente que se reasigna una tarjeta que ya tenía no arranca otra vez. Pero si la tarjeta llegó libre a otra etapa
        // que él también cubre (en Marketing: Brief → Redacción), la cadena se la asigna en su nombre y es trabajo nuevo.
        if (agent.isEmpty() || (agent.get().id().equals(event.actorId()) && agent.get().id().equals(event.fromAssigneeId()))) return;
        AgentTeamStore.Profile profile = tx.execute(s -> team.profile(agent.get().id()));
        if (profile.mode() != AgentMode.SERVER) return; // trabaja desde la PC de una persona (/pendientes)

        AgentRun run = tx.execute(s -> {
            if (runs.existsByTriggerEventId(event.eventId())) return null; // evento reentregado
            AgentRun r = AgentRun.start(agent.get().id(), event.issueKey(), event.projectKey(), event.eventId(), event.actorId(), clock.instant());
            runs.save(r);
            return r;
        });
        if (run == null) return;

        // Lo pidió una persona, o lo encadenó otro agente: entonces responde a la persona que supervisa el issue.
        Optional<MemberView> requester = tx.execute(s -> requesterOf(event));
        if (requester.isEmpty()) {
            finish(run.skipped("Nadie supervisa este issue: asígnamelo tú para que trabaje y sepa a quién responder.", clock.instant()));
            return;
        }
        Optional<WorkspaceAi.Key> key = tx.execute(s -> ai.key());
        if (key.isEmpty()) {
            String why = "El espacio todavía no tiene clave de IA. Quien administra la pone en Conexiones → IA y vuelve a asignarme la tarjeta.";
            tx.executeWithoutResult(s -> delivery.returnCard(agent.get(), requester.get(), event.issueKey(), delivery.failed(true, why)));
            finish(run.skipped(why, clock.instant()));
            return;
        }
        Optional<String> blocked = tx.execute(s -> ai.budgetBlock(agent.get().id(), event.issueKey()));
        if (blocked != null && blocked.isPresent()) {
            String why = blocked.get();
            tx.executeWithoutResult(s -> delivery.returnCard(agent.get(), requester.get(), event.issueKey(), delivery.failed(true, why)));
            finish(run.skipped(why, clock.instant()));
            return;
        }
        if (Boolean.TRUE.equals(tx.execute(s -> ai.overRate()))) {
            String why = "Los agentes de este espacio ya trabajaron muchas tarjetas en la última hora. Vuelve a asignarme la tarjeta en un rato.";
            tx.executeWithoutResult(s -> delivery.returnCard(agent.get(), requester.get(), event.issueKey(), delivery.failed(true, why)));
            finish(run.skipped(why, clock.instant()));
            return;
        }
        // En una puerta con PR, el revisor trabaja con el CI terminado: mientras corre, la tarjeta espera sin gastar tokens.
        Optional<String> waited = gates.awaitCi(agent.get(), requester.get(), event);
        if (waited.isPresent()) {
            finish(run.skipped(waited.get(), clock.instant()));
            return;
        }
        try {
            BoardTeam.Choice choice = tx.execute(s -> team.choice(agent.get().id()));
            BoardAgentModel.Task task = tx.execute(s -> task(agent.get(), event.issueKey(), choice.effort()));
            if (task == null) {
                finish(run.skipped("La tarjeta " + event.issueKey() + " ya no existe.", clock.instant()));
                return;
            }
            String usedModel = key.get().modelFor(choice.model());
            log.info("{} trabajando en {} en el tablero con {} (ejecución {})", agent.get().name(), event.issueKey(), key.get().provider().code(), run.id());
            BoardAgentModel.Result result = providers.adapter(key.get().provider()).work(key.get().endpoint(), usedModel, task);
            Optional<IssueApi.GateReview> gate = tx.execute(s -> issues.gateReview(event.issueKey(), agent.get().id()));
            String summary = gate.isPresent()
                    ? gates.closeGate(agent.get(), event.issueKey(), gate.get(), result)
                    : tx.execute(s -> delivery.deliver(agent.get(), requester.get(), event.issueKey(), result));
            finish(run.succeeded(result.model(), result.inputTokens(), result.outputTokens(), result.cacheReadTokens(), result.cacheWriteTokens(), summary,
                    clock.instant()));
        } catch (AgentModelException e) {
            log.warn("{} no pudo trabajar {}: {}", agent.get().name(), event.issueKey(), e.getMessage());
            tx.executeWithoutResult(s -> delivery.returnCard(agent.get(), requester.get(), event.issueKey(), delivery.failed(false, e.getMessage())));
            finish(run.failed(e.getMessage(), clock.instant()));
        } catch (ec.kuador.board.shared.error.DomainException e) {
            // El tablero no dejó pasar la entrega (p. ej. falta lo que la etapa exige): vuelve a la persona con el motivo.
            log.info("{} no pudo entregar {}: {}", agent.get().name(), event.issueKey(), e.getMessage());
            tx.executeWithoutResult(s -> delivery.returnCard(agent.get(), requester.get(), event.issueKey(), delivery.failed(false, e.getMessage())));
            finish(run.failed(e.getMessage(), clock.instant()));
        } catch (RuntimeException e) {
            log.error("Error inesperado de {} en {}", agent.get().name(), event.issueKey(), e);
            tx.executeWithoutResult(s -> delivery.returnCard(agent.get(), requester.get(), event.issueKey(), delivery.failed(false, "un error inesperado.")));
            finish(run.failed("Error inesperado: " + e.getMessage(), clock.instant()));
        }
    }

    /** El pedido: manual del rol y reglas del tablero (sistema), la tarjeta con sus comentarios y sus archivos. */
    BoardAgentModel.Task task(MemberView agent, String issueKey) {
        return brief.task(agent, issueKey);
    }

    BoardAgentModel.Task task(MemberView agent, String issueKey, String effort) {
        return brief.task(agent, issueKey, effort);
    }

    /** Ver {@link AgentBrief#inputOrder}. */
    static List<AttachmentApi.AttachmentInfo> inputOrder(List<AttachmentApi.AttachmentInfo> files, java.util.Set<java.util.UUID> agentIds) {
        return AgentBrief.inputOrder(files, agentIds);
    }

    /** Ver {@link AgentTools#validArguments}. */
    static boolean validArguments(Map<String, Object> schema) {
        return AgentTools.validArguments(schema);
    }

    /** Quien pidió el trabajo: la persona que asignó, o si lo asignó la cadena (otro agente), la que supervisa el issue. */
    private Optional<MemberView> requesterOf(IssueAssigned event) {
        Optional<MemberView> actor = event.actorId() == null ? Optional.empty() : members.find(event.actorId());
        if (actor.isPresent() && !actor.get().agent()) return actor;
        return issues.supervisorOf(event.issueKey()).flatMap(members::find).filter(m -> !m.agent());
    }

    private void finish(AgentRun run) {
        tx.executeWithoutResult(s -> runs.save(run));
        record(run);
    }

    /** Para el monitoreo (Grafana): cuántos trabajos terminan y cómo, cuánto tardan y cuántos tokens usan. Sin datos del espacio. */
    private void record(AgentRun run) {
        String outcome = run.status().name();
        metrics.counter("kuador.agent.runs", "outcome", outcome).increment();
        if (run.status() == AgentRun.Status.SKIPPED || run.finishedAt() == null) return;
        metrics.timer("kuador.agent.duration", "outcome", outcome).record(java.time.Duration.between(run.createdAt(), run.finishedAt()));
        if (run.model() != null && run.inputTokens() != null) {
            metrics.counter("kuador.agent.tokens", "model", run.model(), "direction", "input").increment(run.inputTokens());
            metrics.counter("kuador.agent.tokens", "model", run.model(), "direction", "output").increment(run.outputTokens() == null ? 0 : run.outputTokens());
        }
    }
}