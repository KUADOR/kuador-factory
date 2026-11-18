package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.AgentModelException;
import ec.kuador.board.agents.domain.AgentRun;
import ec.kuador.board.agents.domain.AgentRunRepository;
import ec.kuador.board.agents.domain.PoContext;
import ec.kuador.board.agents.domain.PoPrompt;
import ec.kuador.board.agents.domain.PoProposal;
import ec.kuador.board.agents.domain.ProductOwnerModel;
import ec.kuador.board.catalog.AgentRole;
import java.util.Map;
import ec.kuador.board.catalog.AgentRules;
import ec.kuador.board.catalog.AgentPolicy;
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
import ec.kuador.board.workspace.WorkspaceApi;
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
    /** Lo que deja una ejecución que terminó con preguntas: así se cuentan las vueltas sin depender de los textos editables. */
    static final String ASKED = "pregunta(s) abiertas";

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
    private final StageHandoff handoff;
    private final WorkspaceApi workspace;
    private final ec.kuador.board.sync.RepositoriesApi repositories;

    ProductOwnerAgent(AgentRunRepository runs, ProductOwnerModel model, IssueApi issues, DocsApi docs, ProjectApi projects,
            MemberApi members, CatalogApi catalog, PlatformTransactionManager txManager, Clock clock, AiKeyProvider keys, WorkspaceApi workspace,
            ec.kuador.board.sync.RepositoriesApi repositories) {
        this.workspace = workspace;
        this.repositories = repositories;
        this.keys = keys;
        this.handoff = new StageHandoff(issues, projects);
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

        // Lo pidió una persona, o lo encadenó otro agente: entonces responde a la persona que supervisa el issue.
        Optional<MemberView> requester = tx.execute(s -> requesterOf(event));
        if (requester.isEmpty()) {
            finish(run.skipped("Nadie supervisa este issue: asígnamelo tú para que trabaje y sepa a quién responder.", now()));
            return;
        }
        Optional<AgentRole> found = tx.execute(s -> catalog.role(agent.get().role()));
        Optional<AgentRole> role = found.filter(r -> r.automation() == AgentRole.Automation.REFINE_ISSUE);
        if (role.isEmpty()) {
            String roleName = found.map(AgentRole::name).orElse(agent.get().role());
            finish(run.skipped("El rol «" + roleName + "» no tiene automatización configurada en el catálogo.", now()));
            return;
        }
        // Siempre la clave de la empresa (Conexiones → IA): no hay clave del servidor.
        Optional<String> key = tx.execute(s -> keys.currentKey());
        Optional<String> blocked = tx.execute(s -> keys.budgetBlock(agent.get().id(), event.issueKey()));
        if (blocked != null && blocked.isPresent()) {
            finish(run.skipped(blocked.get(), now()));
            return;
        }
        if (Boolean.TRUE.equals(tx.execute(s -> keys.overRate()))) {
            finish(run.skipped("Los agentes de este espacio ya trabajaron muchas tarjetas en la última hora: vuelve a asignarla en un rato.", now()));
            return;
        }
        if (key.isEmpty()) {
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
            String effort = key.isPresent() ? tx.execute(s -> keys.effortForAgent(agent.get().id())).orElse(null) : null;
            ProductOwnerModel.Result result = model.propose(key.get(), chosen, effort, context);
            PoProposal proposal = validate(result.proposal());
            if (context.splitsIntoTasks() && proposal.tasks().isEmpty()) {
                throw new AgentModelException("El plan de la épica llegó sin tareas; reintenta para que reparta el trabajo.");
            }
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
        // Las piezas del proyecto (stack, versiones y notas del equipo): el Arquitecto planifica con lo que de verdad se usa.
        String piecesText = Pieces.markdown(repositories, project.key());
        if (!piecesText.isEmpty()) {
            projectDocs = new java.util.ArrayList<>(projectDocs);
            projectDocs.add(new PoContext.Doc("PIEZAS", "Piezas del proyecto", "(tablero)", piecesText));
        }
        // El objetivo del tablero va primero: es la brújula para todo lo demás.
        Optional<String> goal = projects.goal(project.key());
        if (goal.isPresent()) {
            projectDocs = new java.util.ArrayList<>(projectDocs);
            projectDocs.add(0, new PoContext.Doc("OBJETIVO", "Objetivo del tablero", "(tablero)", goal.get()));
        }
        // El documento que le toca: el de issue que la metodología asigna a su rol.
        Optional<DocumentDef> docDef = methodology.flatMap(m -> m.documents(DocumentDef.Scope.ISSUE).stream()
                .filter(d -> role.key().equals(d.writtenBy())).findFirst());
        PoContext.DocTask task = docDef.map(d -> new PoContext.DocTask(d.kind(), d.label(), d.condition() == DocumentDef.Condition.SUBSTANTIAL,
                d.agentInstructions())).orElse(null);
        String existing = task == null ? null : allDocs.stream()
                .filter(d -> issueKey.equals(d.issueKey()) && task.kind().equals(d.kind())).map(DocsApi.DocContent::content).findFirst().orElse(null);
        PoContext.Issue epic = issue.parentKey() == null ? null : issues.context(issue.parentKey()).map(ProductOwnerAgent::view).orElse(null);
        AgentPolicy policy = catalog.agentPolicy();
        WorkspaceApi.Identity company = workspace.identity();
        int read = policy.limits().commentsRead();
        List<IssueApi.CommentView> all = issue.comments();
        List<PoContext.Comment> comments = all.subList(Math.max(0, all.size() - read), all.size()).stream().map(c -> {
            Optional<MemberView> author = members.find(c.authorId());
            return new PoContext.Comment(author.map(MemberView::name).orElse("Miembro eliminado"), author.map(MemberView::agent).orElse(false), c.body());
        }).toList();
        String column = project.column(issue.status()).map(c -> c.name()).orElse(issue.status());
        PoContext.Issue current = new PoContext.Issue(issue.key(), issue.title(), issue.description(), issue.type(), column,
                issue.priority(), issue.storyPoints(), issue.labels());
        return new PoContext(agent.name(), agent.description(), role.instructions(), project.key(), project.name(), project.kind().name(),
                methodology.map(Methodology::name).orElse(null), methodology.map(Methodology::summary).orElse(null),
                methodology.map(Methodology::agentRules).orElse(List.of()), projectDocs, current, epic, comments, task, existing, taskRoles(issue, project),
                new PoContext.Guide(AgentRules.common(policy, company.teamLanguage(), company.codeLanguage()), policy.rules().proposalFields(),
                        policy.rules().document(), policy.rules().taskSplit()));
    }

    /** En una épica que después de esta etapa se trabaja, los roles con que se reparte; si no, ninguno. */
    private List<String> taskRoles(IssueApi.IssueContext issue, ProjectView project) {
        if (!"EPIC".equals(issue.type()) || !project.epicSplitsInto(issue.status())) return List.of();
        return catalog.roles().stream().map(r -> r.key() + ": " + r.name()).toList();
    }

    /** Acciones deterministas a partir de la propuesta. Devuelve el resumen para el registro. */
    String apply(MemberView agent, MemberView requester, PoContext context, PoProposal p) {
        String key = context.issue().key();
        List<String> done = new ArrayList<>();
        PoPrompt.DocLink docLink = null;
        String tasksNote = null;
        PoContext.DocTask task = context.docTask();
        boolean wantsDoc = task != null && !(task.substantialOnly() && p.complexity() == PoProposal.Complexity.SIMPLE);
        if (wantsDoc && p.document() != null && !p.document().isBlank()) {
            DocsApi.DocContent doc = docs.write(context.projectKey(), key, task.kind(), p.document().strip() + "\n",
                    "Propuesta de " + agent.name(), agent.id());
            docLink = new PoPrompt.DocLink(task.label(), doc.version(), context.projectKey(), doc.id(), doc.path());
            done.add(task.label().toLowerCase() + " v" + doc.version());
        }
        if (context.splitsIntoTasks()) {
            List<IssueApi.PlannedTask> planned = p.tasks().stream()
                    .map(t -> new IssueApi.PlannedTask(t.title(), t.description(), t.role(), t.dependsOn() == null ? List.of() : t.dependsOn())).toList();
            List<IssueApi.IssueRef> created;
            try {
                created = issues.createEpicTasks(key, planned, agent.id());
            } catch (ec.kuador.board.shared.error.DomainException e) {
                throw new AgentModelException("No pude crear las tareas del plan: " + e.getMessage(), e);
            }
            StringBuilder note = new StringBuilder("🧩 Repartí la épica en ").append(created.size()).append(" tarea(s); arrancan cuando apruebes el reparto:");
            for (int i = 0; i < created.size(); i++) {
                note.append("\n- ").append(created.get(i).key()).append(" ").append(created.get(i).title()).append(" (").append(p.tasks().get(i).role()).append(")");
            }
            tasksNote = note.toString();
            done.add(created.size() + " tarea(s)");
        }
        AgentPolicy policy = catalog.agentPolicy();
        AgentPolicy.Messages messages = policy.messages();
        // Freno: ya preguntó varias veces y le respondieron; lo que siga abierto no detiene el trabajo.
        long rounds = runs.findRecent(agent.id(), key, 50).stream().filter(r -> r.summary() != null && r.summary().contains(ASKED)).count();
        if (!p.openQuestions().isEmpty() && rounds >= policy.limits().questionRounds() && handoff.next(agent, key).isPresent()) {
            StringBuilder pending = new StringBuilder("**").append(AgentRules.fill(messages.pendingTitle(), Map.of("vueltas", rounds))).append(":**\n");
            for (String q : p.openQuestions()) pending.append("- ").append(q.strip()).append('\n');
            tasksNote = tasksNote == null ? pending.toString().strip() : tasksNote + "\n\n" + pending.toString().strip();
            done.add("avanzó con " + p.openQuestions().size() + " pendiente(s)");
            p = new PoProposal(p.understanding(), p.userStory(), p.acceptanceCriteria(), List.of(), p.outOfScope(), p.storyPoints(), p.complexity(),
                    p.document(), p.tasks());
        }
        boolean pointsApplied = issues.setStoryPointsIfEmpty(key, p.storyPoints(), agent.id());
        if (pointsApplied) done.add(p.storyPoints() + " pts");
        // Sin preguntas y en su etapa: el issue pasa solo a la siguiente. Con preguntas, vuelve a la persona.
        java.util.Optional<StageHandoff.Next> next = p.openQuestions().isEmpty() ? handoff.next(agent, key) : java.util.Optional.empty();
        if (next.isPresent()) {
            String comment = PoPrompt.comment(agent.name(), p, docLink, tasksNote, pointsApplied, StageHandoff.closing(next.get(), requester.name(), messages),
                    messages);
            handoff.deliver(agent, requester, key, next.get(), comment);
            done.add(0, "comentó");
            done.add("pasó a " + next.get().columnName());
            return String.join(" · ", done);
        }
        String closing = AgentRules.fill(p.openQuestions().isEmpty() ? messages.returnForReview() : messages.returnWithQuestions(),
                Map.of("persona", requester.name() == null ? "" : requester.name()));
        issues.addComment(key, agent.id(), PoPrompt.comment(agent.name(), p, docLink, tasksNote, pointsApplied, closing, messages));
        done.add(0, "comentó");
        // Solo lo devuelve si sigue asignado a este agente (alguien pudo reasignarlo mientras trabajaba).
        boolean stillMine = issues.context(key).map(i -> agent.id().equals(i.assigneeId())).orElse(false);
        if (stillMine) {
            issues.reassign(key, requester.id(), agent.id());
            done.add("devuelto a " + requester.name());
        }
        if (!p.openQuestions().isEmpty()) done.add(p.openQuestions().size() + " " + ASKED);
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
                points, p.complexity() == null ? PoProposal.Complexity.SUBSTANTIAL : p.complexity(), nz(p.document()),
                p.tasks() == null ? List.of() : p.tasks());
    }

    /** Quien pidió el trabajo: la persona que asignó, o si lo asignó la cadena (otro agente), la que supervisa el issue. */
    private Optional<MemberView> requesterOf(IssueAssigned event) {
        Optional<MemberView> actor = event.actorId() == null ? Optional.empty() : members.find(event.actorId());
        if (actor.isPresent() && !actor.get().agent()) return actor;
        return issues.supervisorOf(event.issueKey()).flatMap(members::find).filter(m -> !m.agent());
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
