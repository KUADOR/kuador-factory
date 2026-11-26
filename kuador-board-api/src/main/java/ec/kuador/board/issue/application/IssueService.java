package ec.kuador.board.issue.application;

import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.domain.Comment;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.issue.domain.IssueDetails;
import ec.kuador.board.issue.domain.IssueFilter;
import ec.kuador.board.issue.domain.IssueRepository;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.ForbiddenException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso del tablero. Cada cambio publica su evento en la misma transacción: si el cambio
 * no se confirma, el evento tampoco existe.
 *
 * <p>Es la entrada y quien abre la transacción; el trabajo está repartido por responsabilidad: {@link IssueEditor}
 * (crear, editar, asignar), {@link IssueMover} (mover), {@link StageWork} (tomar, entregar, revisar), {@link Gates}
 * (puertas), {@link StageEvidence} (lo que una etapa exige), {@link EpicPlan} y {@link EpicTasks} (el reparto de una
 * épica), {@link Supervision}, {@link AgentQueues} y {@link Decisions}.
 */
@Service
public class IssueService extends IssueSupport implements IssueApi {

    private final Supervision supervision;
    private final StageEvidence evidence;
    private final Gates gates;
    private final EpicTasks tasks;
    private final IssueEditor editor;
    private final IssueDeletion deletion;
    private final IssueMover mover;
    private final EpicPlan plan;
    private final StageWork work;
    private final AgentQueues queues;
    private final Decisions decisions;
    private final BoardReader reader;
    private final IssueComments comments;

    IssueService(IssueRepository issues, ProjectApi projects, MemberApi members, CatalogApi catalog, ApplicationEventPublisher events, Clock clock) {
        Ports ports = new Ports(issues, projects, members, catalog, events, clock);
        super(ports);
        this.supervision = new Supervision(ports);
        this.evidence = new StageEvidence(ports);
        this.gates = new Gates(ports);
        this.tasks = new EpicTasks(ports);
        this.editor = new IssueEditor(ports, gates, supervision);
        this.deletion = new IssueDeletion(ports, this.evidence);
        this.mover = new IssueMover(ports, gates, supervision, tasks, evidence);
        this.plan = new EpicPlan(ports, editor, mover, tasks);
        this.work = new StageWork(ports, gates, evidence, mover, tasks);
        this.queues = new AgentQueues(ports, tasks, gates, evidence, supervision);
        this.decisions = new Decisions(ports, gates, tasks);
        this.reader = new BoardReader(ports);
        this.comments = new IssueComments(ports);
    }

    /** Lo que vive en otros módulos (adjuntos, documentos): cada uno dice lo que la tarjeta tiene. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEvidence(List<ec.kuador.board.issue.CardEvidence> evidence) {
        this.evidence.setEvidence(evidence);
    }

    /** Cuántas terminadas trae el tablero por columna de cierre: el resto se ve en la lista. */
    public static final int DONE_VISIBLE = 20;

    /**
     * Lo que el tablero necesita sin traer el historial entero. {@code totals}: cuántas tarjetas hay en cada columna de
     * cierre; {@code progress}: tareas hechas y totales de cada épica (las hechas pueden no venir en {@code issues});
     * {@code parents}: el título de las épicas que no vienen pero tienen tareas a la vista.
     */
    public record BoardView(List<Issue> issues, java.util.Map<String, Long> totals, java.util.Map<String, long[]> progress,
            java.util.Map<String, String> parents, int doneVisible) {}

    @Transactional(readOnly = true)
    public Issue get(String key) {
        return load(key);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> commentBody(UUID commentId) {
        return issues.findComment(commentId).map(Comment::body);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IssueContext> context(String issueKey) {
        return issues.findByKey(issueKey).map(i -> new IssueContext(i.key(), i.projectKey(), i.title(), i.description(), i.type().name(),
                i.status(), i.priority().name(), i.storyPoints(), i.labels(), i.parentKey(), i.assigneeId(), i.reporterId(),
                issues.findComments(i).stream().map(c -> new CommentView(c.authorId(), c.body(), c.createdAt())).toList(),
                projects.subjectKey(i.projectKey(), i.subjectKey())));
    }

    @Override
    @Transactional
    public java.util.Map<String, String> setCardFields(String issueKey, java.util.Map<String, String> values, UUID actorId) {
        Issue issue = load(issueKey);
        MemberView actor = requireMember(actorId);
        if (actor.agent() && !actorId.equals(issue.assigneeId())) throw new ForbiddenException("Un agente solo cambia los datos de las tarjetas que tomó.");
        List<ec.kuador.board.catalog.FieldDef> defs = project(issue.projectKey()).fieldsOf(issue.type());
        if (defs.isEmpty()) throw new DomainException("Esta tarjeta no tiene datos propios.");
        java.util.Map<String, String> merged = new java.util.LinkedHashMap<>(issue.fields());
        for (var e : (values == null ? java.util.Map.<String, String>of() : values).entrySet()) {
            String name = e.getKey() == null ? "" : e.getKey().strip();
            ec.kuador.board.catalog.FieldDef f = defs.stream().filter(d -> d.key().equalsIgnoreCase(name) || d.label().equalsIgnoreCase(name)).findFirst()
                    .orElseThrow(() -> new DomainException("La tarjeta no tiene el dato «" + name + "». Tiene: "
                            + String.join(", ", defs.stream().map(ec.kuador.board.catalog.FieldDef::label).toList()) + "."));
            merged.put(f.key(), e.getValue() == null ? "" : e.getValue().strip());
        }
        editor.edit(issueKey, new IssueDetails(issue.title(), issue.description(), issue.type(), issue.priority(), issue.storyPoints(), issue.assigneeId(),
                issue.parentKey(), issue.labels(), merged), actorId);
        return cardFields(issueKey);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<String, String> cardFields(String issueKey) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        issues.findByKey(issueKey).ifPresent(i -> projects.find(i.projectKey()).ifPresent(p -> {
            for (ec.kuador.board.catalog.FieldDef f : p.fieldsOf(i.type())) {
                String v = i.fields().get(f.key());
                if (v != null) out.put(f.label(), v);
            }
        }));
        return out;
    }

    @Override
    @Transactional
    public void addComment(String issueKey, UUID authorId, String body) {
        comments.comment(issueKey, authorId, body);
    }

    @Override
    @Transactional
    public void reassign(String issueKey, UUID memberId, UUID actorId) {
        editor.assign(issueKey, memberId, actorId);
    }

    @Override
    @Transactional
    public boolean setStoryPointsIfEmpty(String issueKey, int points, UUID actorId) {
        Issue issue = load(issueKey);
        if (issue.storyPoints() != null) return false;
        editor.edit(issueKey, new IssueDetails(issue.title(), issue.description(), issue.type(), issue.priority(), points, issue.assigneeId(),
                issue.parentKey(), issue.labels()), actorId);
        return true;
    }

    @Override
    @Transactional
    public IssueRef createForImport(String projectKey, String title, UUID actorId) {
        IssueType type = project(projectKey).allows(IssueType.STORY) ? IssueType.STORY : IssueType.TASK;
        Issue issue = editor.create(projectKey, new IssueDetails(title, null, type, null, null, null, null, List.of("importado")), actorId, null);
        return find(issue.key()).orElseThrow();
    }

    @Override
    @Transactional
    public IssueRef createFromMail(String projectKey, String title, String description, String status, UUID assigneeId, UUID actorId) {
        IssueType type = project(projectKey).allows(IssueType.TASK) ? IssueType.TASK : IssueType.STORY;
        Issue issue = editor.create(projectKey, new IssueDetails(title, description, type, null, null, null, null, List.of("correo")), actorId, status);
        if (assigneeId != null) editor.assign(issue.key(), assigneeId, actorId);
        return find(issue.key()).orElseThrow();
    }

    @Override
    @Transactional
    public IssueRef createForRoutine(String projectKey, String title, String description, String status, UUID assigneeId, UUID actorId) {
        IssueType type = project(projectKey).allows(IssueType.TASK) ? IssueType.TASK : IssueType.STORY;
        Issue issue = editor.create(projectKey, new IssueDetails(title, description, type, null, null, null, null, List.of("rutina")), actorId, status);
        if (assigneeId != null) editor.assign(issue.key(), assigneeId, actorId);
        return find(issue.key()).orElseThrow();
    }

    @Override
    @Transactional
    public IssueRef createSuggested(String projectKey, String title, String description, UUID actorId) {
        IssueType type = project(projectKey).allows(IssueType.TASK) ? IssueType.TASK : IssueType.STORY;
        String t = title == null ? "" : title.strip();
        if (t.length() > 255) t = t.substring(0, 255);
        Issue issue = editor.create(projectKey, new IssueDetails(t, description, type, null, null, null, null, List.of("sugerida")), actorId, null);
        return find(issue.key()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isClosed(String issueKey) {
        Optional<Issue> issue = issues.findByKey(issueKey);
        if (issue.isEmpty()) return true;
        ProjectView project = project(issue.get().projectKey());
        return project.column(issue.get().status()).map(c -> c.category() == ec.kuador.board.catalog.ColumnCategory.DONE).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IssueRef> find(String issueKey) {
        return issues.findByKey(issueKey).map(i -> new IssueRef(i.key(), i.projectKey(),
                Integer.parseInt(i.key().substring(i.key().lastIndexOf('-') + 1)), i.title(), projects.subjectKey(i.projectKey(), i.subjectKey())));
    }

    /** Tareas del plan, si el reparto espera aprobación y, si todavía no se puede aprobar, por qué. */
    public record PlanStatus(List<TaskInfo> tasks, boolean awaitingApproval, String notYet) {}

    /**
     * Algo que espera la decisión de una persona. {@code kind}: GATE (una puerta que aprueba), PLAN (el reparto de una
     * épica) o HANDED (un agente le dejó la tarjeta: una pregunta, un escalamiento o un trabajo devuelto).
     */
    public record Decision(String kind, String issueKey, String projectKey, String projectName, String title, String column,
            String columnName, String detail, String from, Instant since) {}

    /** La tarjeta está en una puerta que, para ella, revisa un agente, y hay un agente con ese rol en el espacio. */
    @Override
    @Transactional(readOnly = true)
    public boolean agentReviews(String issueKey) {
        return issues.findByKey(issueKey).map(i -> gates.agentReviewed(i, project(i.projectKey()))).orElse(false);
    }

    @Override
    @Transactional
    public Progress approveGate(String issueKey, String comment, UUID actorId) {
        return work.progress(work.review(issueKey, true, comment, actorId));
    }

    @Override
    @Transactional
    public Progress reviewAs(String issueKey, boolean approve, String comment, UUID agentId) {
        return work.progress(work.review(issueKey, approve, comment, agentId));
    }

    @Override
    @Transactional
    public void handOver(String issueKey, UUID personId) {
        Issue issue = load(issueKey);
        if (personId.equals(issue.supervisorId())) return;
        supervision.supervise(issueKey, personId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> supervisorOf(String issueKey) {
        return issues.findByKey(issueKey).map(Issue::supervisorId);
    }

    @Override
    @Transactional
    public Progress takeAs(String issueKey, UUID agentId) {
        return work.progress(work.take(issueKey, agentId));
    }

    @Override
    @Transactional
    public Progress deliverAs(String issueKey, String comment, UUID agentId) {
        return work.progress(work.deliver(issueKey, comment, agentId));
    }

    @Override
    @Transactional
    public Progress deliverTo(String issueKey, String column, String comment, UUID agentId) {
        return work.progress(work.deliver(issueKey, column, comment, agentId));
    }

    // ---- Leer el tablero ----

    @Transactional(readOnly = true)
    public List<Issue> board(String projectKey, IssueFilter filter) {
        return reader.board(projectKey, filter);
    }

    /** El tablero: todo el trabajo en curso y, de cada columna de cierre, solo las últimas {@link #DONE_VISIBLE}. */
    @Transactional(readOnly = true)
    public BoardView boardView(String projectKey) {
        return reader.boardView(projectKey);
    }

    /** La vista de lista: todas las tarjetas del tablero, también las terminadas, con filtros, orden y página. */
    @Transactional(readOnly = true)
    public ec.kuador.board.issue.domain.IssuePage search(String projectKey, ec.kuador.board.issue.domain.IssueQuery query) {
        return reader.search(projectKey, query);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BoardCard> boardCards(String projectKey) {
        return reader.boardCards(projectKey);
    }

    // ---- Comentarios ----

    @Transactional(readOnly = true)
    public List<Comment> comments(String key) {
        return comments.comments(key);
    }

    @Transactional
    public Comment comment(String key, UUID authorId, String body) {
        return comments.comment(key, authorId, body);
    }

    /** Su autor corrige el comentario (p. ej. lo acorta para que un agente lea menos). */
    @Transactional
    public Comment editComment(String key, UUID commentId, UUID actorId, String body) {
        return comments.editComment(key, commentId, actorId, body);
    }

    /** Lo borra su autor o quien administra el espacio. */
    @Transactional
    public void deleteComment(String key, UUID commentId, UUID actorId) {
        comments.deleteComment(key, commentId, actorId);
    }

    // ---- Crear, editar, mover ----

    @Transactional
    public Issue create(String projectKey, IssueDetails details, UUID actorId, String status) {
        return editor.create(projectKey, details, actorId, status);
    }

    @Transactional
    public Issue edit(String key, IssueDetails details, UUID actorId) {
        return editor.edit(key, details, actorId);
    }

    @Transactional
    public Issue assign(String key, UUID memberId, UUID actorId) {
        return editor.assign(key, memberId, actorId);
    }

    /** Borra una tarjeta, salvo que la trabaje un agente, sea una épica con tareas en marcha u otra tarea dependa de ella. */
    @Transactional
    public void delete(String key, UUID actorId) {
        deletion.deleteOne(key, actorId);
    }

    /** Lo que pasaría al borrar varias tarjetas juntas, sin borrar nada (para la confirmación). */
    @Transactional(readOnly = true)
    public DeletePlan planDelete(List<String> keys) {
        return DeletePlan.of(deletion.plan(keys));
    }

    /** Borra juntas las que se puedan de {@code keys}, en una sola transacción; las demás vuelven con su motivo. */
    @Transactional
    public DeletePlan deleteMany(List<String> keys, UUID actorId) {
        return DeletePlan.of(deletion.delete(keys, actorId));
    }

    /** {@code deleted}: las pedidas que se borran; {@code withEpics}: tareas en espera que se van con su épica; {@code blocked}: clave → motivo. */
    public record DeletePlan(List<String> deleted, List<String> withEpics, java.util.Map<String, String> blocked) {
        static DeletePlan of(IssueDeletion.Plan p) {
            return new DeletePlan(p.deleted(), p.withEpics(), p.blocked());
        }
    }

    /** Mueve el issue a {@code target} en la posición {@code index} (0 = arriba) y reordena la columna. */
    @Transactional
    public Issue move(String key, String target, int index, UUID actorId) {
        return mover.move(key, target, index, actorId);
    }

    // ---- Etapas: tomar, entregar y revisar en una puerta ----

    /** Toma un issue sin asignar de una etapa: solo quien tenga uno de sus roles responsables. */
    @Transactional
    public Issue take(String key, UUID actorId) {
        return work.take(key, actorId);
    }

    /** Entrega el trabajo de la etapa: el issue pasa a la siguiente que permita el flujo y queda abierto allí. */
    @Transactional
    public Issue deliver(String key, String comment, UUID actorId) {
        return work.deliver(key, comment, actorId);
    }

    /** Cierra una puerta: aprobar pasa a la siguiente etapa; rechazar devuelve el issue con un motivo. */
    @Transactional
    public Issue review(String key, boolean approve, String comment, UUID actorId) {
        return work.review(key, approve, comment, actorId);
    }

    /** Quién revisa esta tarjeta en las puertas: PERSON, AGENT o nulo (lo del tablero). Personas con permiso de edición. */
    @Transactional
    public Issue setReviewBy(String key, String reviewBy, UUID actorId) {
        return work.setReviewBy(key, reviewBy, actorId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> notReadyToDeliver(String issueKey, UUID agentId, String comment) {
        return evidence.notReadyToDeliver(issueKey, agentId, comment);
    }

    /** Devuelve el issue a una etapa anterior con el motivo (p. ej. QA encontró fallas): suma una vuelta atrás. */
    @Override
    @Transactional
    public Progress sendBack(String issueKey, String column, String reason, UUID actorId) {
        return work.sendBack(issueKey, column, reason, actorId);
    }

    @Override
    @Transactional
    public Optional<String> returnForConflict(String issueKey, String note, UUID actorId) {
        return work.returnForConflict(issueKey, note, actorId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean branchIdle(String issueKey) {
        return work.branchIdle(issueKey);
    }

    // ---- Puertas ----

    @Override
    @Transactional(readOnly = true)
    public boolean canApproveGate(String issueKey, UUID actorId) {
        return gates.canApproveGate(issueKey, actorId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GateReview> gateReview(String issueKey, UUID agentId) {
        return gates.gateReview(issueKey, agentId);
    }

    /** El agente revisor no puede decidir: comenta su análisis y deja la tarjeta a una persona que aprueba la puerta. */
    @Override
    @Transactional
    public Progress escalate(String issueKey, String analysis, UUID agentId) {
        return work.escalate(issueKey, analysis, agentId);
    }

    // ---- El reparto de una épica ----

    @Override
    @Transactional
    public List<IssueRef> createEpicTasks(String epicKey, List<PlannedTask> tasks, UUID actorId) {
        return plan.createEpicTasks(epicKey, tasks, actorId);
    }

    /** Una persona aprueba el reparto de tareas de la épica o pide cambios. */
    @Transactional
    public Issue reviewPlan(String epicKey, boolean approve, String comment, UUID actorId) {
        return plan.reviewPlan(epicKey, approve, comment, actorId);
    }

    /** El reparto de una épica para el tablero: sus tareas y si ya se puede aprobar. */
    @Transactional(readOnly = true)
    public PlanStatus planStatus(String epicKey) {
        return tasks.planStatus(epicKey);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canSplit(String issueKey) {
        return tasks.canSplit(issueKey);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskInfo> epicTasks(String epicKey) {
        return tasks.epicTasks(epicKey);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskInfo> taskInfo(String issueKey) {
        return tasks.taskInfo(issueKey);
    }

    // ---- Supervisión, colas de los agentes y decisiones de las personas ----

    /** Una persona se encarga de que sus agentes atiendan el issue (p. ej. si lo dejó otra persona). */
    @Transactional
    public Issue supervise(String key, UUID actorId) {
        return supervision.supervise(key, actorId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QueueItem> agentQueue(UUID supervisorId) {
        return queues.agentQueue(supervisorId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QueueItem> openForAgents(String projectKey) {
        return queues.openForAgents(projectKey);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QueueItem> waitingForAgents(String projectKey) {
        return queues.waitingForAgents(projectKey);
    }

    /** Lo que espera a esta persona en los tableros que ve, lo más antiguo primero. */
    @Transactional(readOnly = true)
    public List<Decision> decisionsFor(UUID memberId) {
        return decisions.decisionsFor(memberId);
    }
}
