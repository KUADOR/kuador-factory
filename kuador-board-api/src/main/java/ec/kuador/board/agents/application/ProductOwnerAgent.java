package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.AgentModelException;
import ec.kuador.board.agents.domain.AgentRun;
import ec.kuador.board.agents.domain.AgentRunRepository;
import ec.kuador.board.agents.domain.PoContext;
import ec.kuador.board.agents.domain.PoPrompt;
import ec.kuador.board.agents.domain.PoProposal;
import ec.kuador.board.agents.domain.ProductOwnerModel;
import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.DocumentDef;
import ec.kuador.board.catalog.Methodology;
import ec.kuador.board.docs.DocsApi;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueAssigned;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Agente que refina issues (automatización REFINE_ISSUE de su rol en el catálogo). Cuando una
 * persona le asigna un issue: arma el contexto, pide una propuesta al modelo y actúa como miembro del
 * equipo (comenta, escribe el documento que su metodología le asigna, estima si no había estimación y
 * devuelve el issue a quien se lo asignó). No mueve el issue de columna: aprobar es de una persona.
 *
 * <p>La llamada al modelo va fuera de cualquier transacción (puede tardar minutos). Si falla, la
 * ejecución queda FAILED y no se reintenta sola: se reintenta volviendo a asignarle el issue.
 */
@Service
public class ProductOwnerAgent {

    private static final Logger log = LoggerFactory.getLogger(ProductOwnerAgent.class);
    private static final Set<String> CONTEXT_DOC_KINDS = Set.of("CONSTITUTION", "PRODUCT", "ARCHITECTURE", "GLOSSARY", "AGENT_RULES");
    private static final int[] FIBONACCI = {1, 2, 3, 5, 8, 13};
    private static final int MAX_COMMENTS = 20;

    private final AgentRunRepository runs;
    private final ProductOwnerModel model;
    private final IssueApi issues;
    private final DocsApi docs;
    private final ProjectApi projects;
    private final MemberApi members;
    private final CatalogApi catalog;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AiKeyProvider keys;

    ProductOwnerAgent(AgentRunRepository runs, ProductOwnerModel model, IssueApi issues, DocsApi docs, ProjectApi projects,
            MemberApi members, CatalogApi catalog, PlatformTransactionManager txManager, Clock clock, AiKeyProvider keys) {
        this.keys = keys;
        this.runs = runs;
        this.model = model;
        this.issues = issues;
        this.docs = docs;
        this.projects = projects;
        this.members = members;
        this.catalog = catalog;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /** Punto de entrada: un issue se asignó a alguien. Trabaja dentro del espacio del evento. */
    public void onAssigned(IssueAssigned event) {
        TenantContext.run(event.workspaceId(), () -> handle(event));
    }

    private void handle(IssueAssigned event) {
        if (event.toAssigneeId() == null) return;
        Optional<MemberView> agent = tx.execute(s -> members.find(event.toAssigneeId())).filter(MemberView::agent);
        if (agent.isEmpty()) return; // asignado a una persona: nada que hacer
        // El agente se tomó el issue (desde el cliente de IA de una persona, por el servidor MCP): ya está trabajando allí.
        if (agent.get().id().equals(event.actorId())) return;

        AgentRun run = tx.execute(s -> {
            if (runs.existsByTriggerEventId(event.eventId())) return null; // evento reentregado
            AgentRun r = AgentRun.start(agent.get().id(), event.issueKey(), event.projectKey(), event.eventId(), event.actorId(), clock.instant());
            runs.save(r);
            return r;
        });
        if (run == null) return;

        Optional<MemberView> requester = event.actorId() == null ? Optional.empty() : tx.execute(s -> members.find(event.actorId()));
        if (requester.isEmpty() || requester.get().agent()) {
            finish(run.skipped("Lo asignó un agente; para evitar bucles entre agentes, solo actúo cuando me asigna una persona.", now()));
            return;
        }
        Optional<AgentRole> found = tx.execute(s -> catalog.role(agent.get().role()));
        Optional<AgentRole> role = found.filter(r -> r.automation() == AgentRole.Automation.REFINE_ISSUE);
        if (role.isEmpty()) {
            String roleName = found.map(AgentRole::name).orElse(agent.get().role());
            finish(run.skipped("El rol «" + roleName + "» no tiene automatización configurada en el catálogo.", now()));
            return;
        }
        // La clave de la empresa (Conexiones → IA); la del servidor solo como respaldo si existe.
        Optional<String> key = tx.execute(s -> keys.currentKey());
        if (Boolean.TRUE.equals(tx.execute(s -> keys.overBudget()))) {
            finish(run.skipped("El espacio llegó a su tope mensual de IA (Conexiones → IA).", now()));
            return;
        }
        if (key.isEmpty() && !model.available()) {
            finish(run.skipped("El Agente PO trabaja con Claude por ahora: falta una clave de Anthropic en Conexiones → IA. Ponla y vuelve a asignar el issue.", now()));
            return;
        }

        try {
            PoContext context = tx.execute(s -> buildContext(agent.get(), role.get(), event.issueKey()));
            if (context == null) {
                finish(run.skipped("El issue " + event.issueKey() + " ya no existe.", now()));
                return;
            }
            log.info("{} trabajando en {} (ejecución {})", agent.get().name(), event.issueKey(), run.id());
            // Con la clave de la empresa, el modelo elegido para este agente (Configuración → Equipo de agentes).
            String chosen = key.isPresent() ? tx.execute(s -> keys.modelForAgent(agent.get().id())).orElse(null) : null;
            ProductOwnerModel.Result result = key.isPresent() ? model.propose(key.get(), chosen, context) : model.propose(context);
            PoProposal proposal = validate(result.proposal());
            String summary = tx.execute(s -> apply(agent.get(), requester.get(), context, proposal));
            finish(run.succeeded(result.model(), result.inputTokens(), result.outputTokens(), summary, now()));
        } catch (AgentModelException e) {
            log.warn("{} no pudo trabajar {}: {}", agent.get().name(), event.issueKey(), e.getMessage());
            finish(run.failed(e.getMessage(), now()));
        } catch (RuntimeException e) {
            log.error("Error inesperado de {} en {}", agent.get().name(), event.issueKey(), e);
            finish(run.failed("Error inesperado: " + e.getMessage(), now()));
        }
    }

    PoContext buildContext(MemberView agent, AgentRole role, String issueKey) {
        Optional<IssueApi.IssueContext> found = issues.context(issueKey);
        if (found.isEmpty()) return null;
        IssueApi.IssueContext issue = found.get();
        ProjectView project = projects.find(issue.projectKey()).orElseThrow();
        Optional<Methodology> methodology = project.software() ? catalog.methodology(project.methodology()) : Optional.empty();
        List<DocsApi.DocContent> allDocs = project.software() ? docs.ofProject(project.key()) : List.of();
        List<PoContext.Doc> projectDocs = allDocs.stream()
                .filter(d -> d.issueKey() == null && CONTEXT_DOC_KINDS.contains(d.kind()))
                .map(d -> new PoContext.Doc(d.kind(), d.title(), d.path(), d.content())).toList();
        // El documento que le toca: el de issue que la metodología asigna a su rol.
        Optional<DocumentDef> docDef = methodology.flatMap(m -> m.documents(DocumentDef.Scope.ISSUE).stream()
                .filter(d -> role.key().equals(d.writtenBy())).findFirst());
        PoContext.DocTask task = docDef.map(d -> new PoContext.DocTask(d.kind(), d.label(), d.condition() == DocumentDef.Condition.SUBSTANTIAL,
                d.agentInstructions())).orElse(null);
        String existing = task == null ? null : allDocs.stream()
                .filter(d -> issueKey.equals(d.issueKey()) && task.kind().equals(d.kind())).map(DocsApi.DocContent::content).findFirst().orElse(null);
        PoContext.Issue epic = issue.parentKey() == null ? null : issues.context(issue.parentKey()).map(ProductOwnerAgent::view).orElse(null);
        List<IssueApi.CommentView> all = issue.comments();
        List<PoContext.Comment> comments = all.subList(Math.max(0, all.size() - MAX_COMMENTS), all.size()).stream().map(c -> {
            Optional<MemberView> author = members.find(c.authorId());
            return new PoContext.Comment(author.map(MemberView::name).orElse("Miembro eliminado"), author.map(MemberView::agent).orElse(false), c.body());
        }).toList();
        String column = project.column(issue.status()).map(c -> c.name()).orElse(issue.status());
        PoContext.Issue current = new PoContext.Issue(issue.key(), issue.title(), issue.description(), issue.type(), column,
                issue.priority(), issue.storyPoints(), issue.labels());
        return new PoContext(agent.name(), agent.description(), role.instructions(), project.key(), project.name(), project.kind().name(),
                methodology.map(Methodology::name).orElse(null), methodology.map(Methodology::summary).orElse(null),
                methodology.map(Methodology::agentRules).orElse(List.of()), projectDocs, current, epic, comments, task, existing);
    }

    /** Acciones deterministas a partir de la propuesta. Devuelve el resumen para el registro. */
    String apply(MemberView agent, MemberView requester, PoContext context, PoProposal p) {
        String key = context.issue().key();
        List<String> done = new ArrayList<>();
        String docNote = null;
        PoContext.DocTask task = context.docTask();
        boolean wantsDoc = task != null && !(task.substantialOnly() && p.complexity() == PoProposal.Complexity.SIMPLE);
        if (wantsDoc && p.document() != null && !p.document().isBlank()) {
            DocsApi.DocContent doc = docs.write(context.projectKey(), key, task.kind(), p.document().strip() + "\n",
                    "Propuesta de " + agent.name(), agent.id());
            docNote = "📄 Escribí «" + task.label() + "» (v" + doc.version() + "): `" + doc.path() + "`.";
            done.add(task.label().toLowerCase() + " v" + doc.version());
        }
        boolean pointsApplied = issues.setStoryPointsIfEmpty(key, p.storyPoints(), agent.id());
        if (pointsApplied) done.add(p.storyPoints() + " pts");
        issues.addComment(key, agent.id(), PoPrompt.comment(agent.name(), p, docNote, requester.name(), pointsApplied));
        done.add(0, "comentó");
        // Solo lo devuelve si sigue asignado a este agente (alguien pudo reasignarlo mientras trabajaba).
        boolean stillMine = issues.context(key).map(i -> agent.id().equals(i.assigneeId())).orElse(false);
        if (stillMine) {
            issues.reassign(key, requester.id(), agent.id());
            done.add("devuelto a " + requester.name());
        }
        if (!p.openQuestions().isEmpty()) done.add(p.openQuestions().size() + " pregunta(s) abiertas");
        return String.join(" · ", done);
    }

    /** La salida ya viene validada contra el esquema; aquí se revisa lo que el esquema no expresa. */
    static PoProposal validate(PoProposal p) {
        if (p == null || p.userStory() == null || p.userStory().isBlank() || p.acceptanceCriteria() == null || p.acceptanceCriteria().isEmpty()) {
            throw new AgentModelException("El modelo devolvió una propuesta sin historia o sin criterios de aceptación.");
        }
        int points = FIBONACCI[FIBONACCI.length - 1];
        for (int f : FIBONACCI) {
            if (p.storyPoints() <= f) {
                points = f;
                break;
            }
        }
        return new PoProposal(nz(p.understanding()), p.userStory(), p.acceptanceCriteria(), orEmpty(p.openQuestions()), orEmpty(p.outOfScope()),
                points, p.complexity() == null ? PoProposal.Complexity.SUBSTANTIAL : p.complexity(), nz(p.document()));
    }

    private void finish(AgentRun run) {
        tx.executeWithoutResult(s -> runs.save(run));
    }

    private Instant now() {
        return clock.instant();
    }

    private static PoContext.Issue view(IssueApi.IssueContext i) {
        return new PoContext.Issue(i.key(), i.title(), i.description(), i.type(), i.status(), i.priority(), i.storyPoints(), i.labels());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static List<String> orEmpty(List<String> l) {
        return l == null ? List.of() : l;
    }
}
