package ec.kuador.board.issue.application;

import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueAssigned;
import ec.kuador.board.issue.IssueCommented;
import ec.kuador.board.issue.IssueCreated;
import ec.kuador.board.issue.IssueDeleted;
import ec.kuador.board.issue.IssueMoved;
import ec.kuador.board.issue.IssueUpdated;
import ec.kuador.board.issue.domain.Comment;
import ec.kuador.board.issue.domain.FieldChange;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.issue.domain.IssueDetails;
import ec.kuador.board.issue.domain.IssueFilter;
import ec.kuador.board.issue.domain.IssueRepository;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.ForbiddenException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso del tablero. Cada cambio publica su evento en la misma transacción: si el cambio
 * no se confirma, el evento tampoco existe.
 */
@Service
public class IssueService implements IssueApi {

    private static final int EXCERPT = 280;

    private final IssueRepository issues;
    private final ProjectApi projects;
    private final MemberApi members;
    private final CatalogApi catalog;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** Tope de tareas por épica: más es ruido en el tablero. */
    static final int MAX_TASKS = 40;

    IssueService(IssueRepository issues, ProjectApi projects, MemberApi members, CatalogApi catalog, ApplicationEventPublisher events, Clock clock) {
        this.issues = issues;
        this.projects = projects;
        this.members = members;
        this.catalog = catalog;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Issue> board(String projectKey, IssueFilter filter) {
        ProjectView project = project(projectKey);
        return issues.findByProject(projectKey, filter == null ? IssueFilter.none() : filter).stream()
                .sorted(Comparator.comparingInt((Issue i) -> project.indexOf(i.status())).thenComparingInt(Issue::position))
                .toList();
    }

    @Transactional(readOnly = true)
    public Issue get(String key) {
        return load(key);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IssueContext> context(String issueKey) {
        return issues.findByKey(issueKey).map(i -> new IssueContext(i.key(), i.projectKey(), i.title(), i.description(), i.type().name(),
                i.status(), i.priority().name(), i.storyPoints(), i.labels(), i.parentKey(), i.assigneeId(), i.reporterId(),
                issues.findComments(i).stream().map(c -> new CommentView(c.authorId(), c.body(), c.createdAt())).toList()));
    }

    @Override
    @Transactional
    public void addComment(String issueKey, UUID authorId, String body) {
        comment(issueKey, authorId, body);
    }

    @Override
    @Transactional
    public void reassign(String issueKey, UUID memberId, UUID actorId) {
        assign(issueKey, memberId, actorId);
    }

    @Override
    @Transactional
    public boolean setStoryPointsIfEmpty(String issueKey, int points, UUID actorId) {
        Issue issue = load(issueKey);
        if (issue.storyPoints() != null) return false;
        edit(issueKey, new IssueDetails(issue.title(), issue.description(), issue.type(), issue.priority(), points, issue.assigneeId(),
                issue.parentKey(), issue.labels()), actorId);
        return true;
    }

    @Override
    @Transactional
    public IssueRef createForImport(String projectKey, String title, UUID actorId) {
        IssueType type = project(projectKey).allows(IssueType.STORY) ? IssueType.STORY : IssueType.TASK;
        Issue issue = create(projectKey, new IssueDetails(title, null, type, null, null, null, null, List.of("importado")), actorId, null);
        return find(issue.key()).orElseThrow();
    }

    @Override
    @Transactional
    public IssueRef createForRoutine(String projectKey, String title, String description, String status, UUID assigneeId, UUID actorId) {
        IssueType type = project(projectKey).allows(IssueType.TASK) ? IssueType.TASK : IssueType.STORY;
        Issue issue = create(projectKey, new IssueDetails(title, description, type, null, null, null, null, List.of("rutina")), actorId, status);
        if (assigneeId != null) assign(issue.key(), assigneeId, actorId);
        return find(issue.key()).orElseThrow();
    }

    @Override
    @Transactional
    public IssueRef createSuggested(String projectKey, String title, String description, UUID actorId) {
        IssueType type = project(projectKey).allows(IssueType.TASK) ? IssueType.TASK : IssueType.STORY;
        String t = title == null ? "" : title.strip();
        if (t.length() > 255) t = t.substring(0, 255);
        Issue issue = create(projectKey, new IssueDetails(t, description, type, null, null, null, null, List.of("sugerida")), actorId, null);
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
                Integer.parseInt(i.key().substring(i.key().lastIndexOf('-') + 1)), i.title()));
    }

    @Transactional
    public Issue create(String projectKey, IssueDetails details, UUID actorId, String status) {
        ProjectView project = project(projectKey);
        validateReferences(project, details, null, null);
        if (actorId != null) {
            requireMember(actorId);
        }
        // Sin columna indicada, el issue entra en la primera del flujo.
        String column = status == null || status.isBlank() ? project.columns().getFirst().key() : requireColumn(project, status).key();
        int number = projects.allocateIssueNumber(projectKey);
        Instant now = now();
        Issue created = Issue.create(projectKey, number, details, actorId, column, issues.nextPosition(projectKey, column), now);
        // Crear la tarjeta ya pasada una puerta sería saltársela: lo hace solo quien la aprueba.
        if (actorId != null) requireGatesCrossed(project, null, column, actorId, created);
        superviseIfPersonHandsToAgents(created, project, actorId);
        Issue issue = issues.save(created);
        events.publishEvent(new IssueCreated(UUID.randomUUID(), TenantContext.require(), issue.key(), projectKey, actorId, now,
                issue.title(), issue.type().name(), issue.status(), requireColumn(project, column).category().name()));
        if (issue.assigneeId() != null) {
            events.publishEvent(new IssueAssigned(UUID.randomUUID(), TenantContext.require(), issue.key(), projectKey, actorId, now, null, issue.assigneeId()));
        }
        return issue;
    }

    @Transactional
    public Issue edit(String key, IssueDetails details, UUID actorId) {
        Issue issue = load(key);
        validateReferences(project(issue.projectKey()), details, issue.key(), issue.assigneeId());
        UUID previousAssignee = issue.assigneeId();
        Instant now = now();
        List<FieldChange> changes = issue.edit(details, now);
        if (!Objects.equals(previousAssignee, issue.assigneeId())) superviseIfPersonHandsToAgents(issue, project(issue.projectKey()), actorId);
        Issue saved = issues.save(issue);
        if (!changes.isEmpty()) {
            events.publishEvent(new IssueUpdated(UUID.randomUUID(), TenantContext.require(), key, issue.projectKey(), actorId, now,
                    changes.stream().map(c -> new IssueUpdated.Change(c.field(), c.from(), c.to())).toList()));
        }
        publishAssignmentIfChanged(issue, previousAssignee, actorId, now);
        return saved;
    }

    @Transactional
    public Issue assign(String key, UUID memberId, UUID actorId) {
        Issue issue = load(key);
        if (memberId != null) {
            requireOnBoard(memberId, issue.projectKey());
        }
        UUID previousAssignee = issue.assigneeId();
        Instant now = now();
        issue.assign(memberId, now);
        superviseIfPersonHandsToAgents(issue, project(issue.projectKey()), actorId);
        Issue saved = issues.save(issue);
        publishAssignmentIfChanged(issue, previousAssignee, actorId, now);
        return saved;
    }

    /** Mueve el issue a {@code target} en la posición {@code index} (0 = arriba) y reordena la columna. */
    @Transactional
    public Issue move(String key, String target, int index, UUID actorId) {
        Issue issue = load(key);
        ProjectView project = project(issue.projectKey());
        ColumnDef to = requireColumn(project, target);
        String from = issue.status();
        // Reglas de transición del flujo: aplican a personas y agentes por igual.
        if (project.column(from).isPresent() && !project.canMove(from, target)) {
            String fromName = project.column(from).map(ColumnDef::name).orElse(from);
            List<String> targets = project.transitions().targets(from, project.columns()).stream()
                    .map(k -> project.column(k).map(ColumnDef::name).orElse(k)).toList();
            throw new DomainException("El flujo no permite pasar de «" + fromName + "» a «" + to.name() + "»."
                    + (targets.isEmpty() ? " Desde esa columna no hay salidas configuradas." : " Desde ahí se puede ir a: " + String.join(", ", targets) + "."));
        }
        Optional<StageDef> fromStage = from.equals(target) ? Optional.empty() : project.stage(from);
        if (fromStage.isPresent() && fromStage.get().gate()) {
            requireApprover(project, fromStage.get(), actorId, issue);
        }
        // Tampoco se salta una puerta llevando la tarjeta de antes a después de ella.
        requireGatesCrossed(project, from, target, actorId, issue);
        requireEpicWaitsForTasks(project, issue, target);
        List<Issue> column = new ArrayList<>(issues.findColumn(issue.projectKey(), target));
        column.removeIf(i -> i.id().equals(issue.id()));
        column.add(Math.clamp(index, 0, column.size()), issue);
        Instant now = now();
        UUID previousAssignee = issue.assigneeId();
        issue.moveTo(target, now);
        if (!from.equals(target)) {
            // Una etapa con responsables queda abierta: la toma cualquiera con ese rol.
            project.stage(target).filter(st -> !st.roles().isEmpty() || st.gate()).ifPresent(st -> issue.assign(null, now));
            // Freno de bucles: demasiadas vueltas atrás y el issue va a una persona.
            if (project.column(from).isPresent() && project.indexOf(target) < project.indexOf(from)
                    && issue.countRework() > project.maxRework()) {
                responsibleHuman(issue).ifPresent(person -> {
                    issue.assign(person.id(), now);
                    saveComment(issue, actorId, "Este issue volvió atrás " + issue.reworkCount() + " veces (el límite es " + project.maxRework()
                            + "). Lo asigné a " + person.name() + " para que revise qué está pasando antes de seguir.", now);
                });
            }
        }
        if (!from.equals(target)) superviseIfPersonHandsToAgents(issue, project, actorId);
        for (int i = 0; i < column.size(); i++) {
            column.get(i).reposition(i);
        }
        issues.saveAll(column);
        if (!from.equals(target)) {
            // La columna de origen pudo borrarse de un flujo viejo: entonces su categoría queda nula.
            String fromCategory = project.column(from).map(c -> c.category().name()).orElse(null);
            events.publishEvent(new IssueMoved(UUID.randomUUID(), TenantContext.require(), key, issue.projectKey(), actorId, now, from, target,
                    fromCategory, to.category().name()));
            publishAssignmentIfChanged(issue, previousAssignee, actorId, now);
            if (issue.type() == IssueType.EPIC) announcePendingPlan(issue, project, actorId);
            if (issue.workRole() != null && issue.parentKey() != null && releaseColumn(project).filter(target::equals).isPresent()) {
                // Una tarea termina con su PR aprobado: el release (develop → main) se hace una vez, con la épica.
                firstDone(project).ifPresent(done -> {
                    place(issue, project, done.key(), actorId);
                    saveComment(issue, actorId, "Tarea terminada: su PR quedó aprobado. El release se hace con la épica " + issue.parentKey() + ".", now());
                });
            }
            if (issue.parentKey() != null && isDone(project, issue.status())) closeEpicIfDone(issue.parentKey(), project, actorId);
        }
        return issue;
    }

    // ---- Tareas de una épica (desde el plan) ----

    @Override
    @Transactional
    public List<IssueRef> createEpicTasks(String epicKey, List<PlannedTask> tasks, UUID actorId) {
        Issue epic = load(epicKey);
        ProjectView project = project(epic.projectKey());
        boolean promote = epic.type() != IssueType.EPIC;
        if (promote && !splittable(epic, project)) {
            throw new DomainException(epicKey + " no es una épica: las tareas del plan se crean en una épica (o en una historia sin épica, que pasa a serlo).");
        }
        if (tasks == null || tasks.isEmpty()) throw new DomainException("Indica al menos una tarea.");
        if (tasks.size() > MAX_TASKS) throw new DomainException("Máximo " + MAX_TASKS + " tareas por épica: agrupa las más pequeñas.");
        if (!project.allows(IssueType.TASK)) throw new DomainException("Este proyecto no usa tareas (TASK).");
        String first = project.columns().getFirst().key();
        List<Issue> previous = issues.findChildren(epicKey).stream().filter(c -> c.workRole() != null).toList();
        if (previous.stream().anyMatch(c -> !c.status().equals(first))) {
            throw new ConflictException(epicKey + " ya tiene tareas en marcha: agrega las nuevas desde el tablero o devuélvelas antes de rehacer el plan.");
        }
        List<String> roles = catalog.roles().stream().map(r -> r.key()).toList();
        for (int i = 0; i < tasks.size(); i++) {
            PlannedTask t = tasks.get(i);
            if (t.title() == null || t.title().isBlank()) throw new DomainException("La tarea " + (i + 1) + " no tiene título.");
            String role = t.role() == null ? "" : t.role().strip().toUpperCase(java.util.Locale.ROOT);
            if (!roles.contains(role)) throw new DomainException("La tarea " + (i + 1) + " tiene un rol que no existe: «" + t.role() + "». Roles: " + String.join(", ", roles) + ".");
            if (buildColumn(project).isPresent() && !writesCode(role)) {
                List<String> coders = roles.stream().filter(this::writesCode).toList();
                throw new DomainException("La tarea " + (i + 1) + " («" + t.title() + "») es para " + role + ", que no escribe en el repo: nadie abriría su rama ni su PR."
                        + " Cada tarea la hace un rol que escribe código (" + String.join(", ", coders) + "). El diseño (contrato, modelo de datos, ADR) va en el plan;"
                        + " si debe quedar en el repo, es la primera tarea de backend y las demás dependen de ella.");
            }
            for (Integer d : t.dependsOn() == null ? List.<Integer>of() : t.dependsOn()) {
                if (d == null || d < 1 || d > tasks.size() || d == i + 1) throw new DomainException("La tarea " + (i + 1) + " depende de un número inválido: " + d + ".");
            }
        }
        if (promote) {
            String was = epic.type() == IssueType.STORY ? "una historia" : "una tarea";
            edit(epicKey, new IssueDetails(epic.title(), epic.description(), IssueType.EPIC, epic.priority(), epic.storyPoints(), epic.assigneeId(), null,
                    epic.labels()), actorId);
            saveComment(epic, actorId, "Era " + was + ": pasa a **épica** porque el plan la reparte en " + tasks.size() + " tarea(s).", now());
            epic = load(epicKey);
        }
        previous.forEach(c -> delete(c.key(), actorId));
        List<Issue> created = new ArrayList<>();
        for (PlannedTask t : tasks) {
            Issue task = create(epic.projectKey(), new IssueDetails(t.title().strip(), t.description(), IssueType.TASK, epic.priority(), null, null, epicKey, List.of()),
                    actorId, first);
            created.add(task);
        }
        for (int i = 0; i < tasks.size(); i++) {
            Issue task = created.get(i);
            List<String> deps = (tasks.get(i).dependsOn() == null ? List.<Integer>of() : tasks.get(i).dependsOn()).stream().map(d -> created.get(d - 1).key()).toList();
            task.planAsTask(tasks.get(i).role(), deps);
            task.superviseBy(epic.supervisorId());
            issues.save(task);
        }
        // Si la épica ya está donde se construye (p. ej. se repartió estando en Desarrollo), el reparto se revisa ya:
        // moverla «a la siguiente columna» la llevaría a QA.
        if (planStage(project, epic.status())) announcePendingPlan(epic, project, actorId);
        return created.stream().map(c -> new IssueRef(c.key(), c.projectKey(), Integer.parseInt(c.key().substring(c.key().lastIndexOf('-') + 1)), c.title())).toList();
    }

    /** Una historia o tarea suelta (sin épica ni rol de reparto) que el plan puede repartir: pasa a ser épica. */
    private static boolean splittable(Issue issue, ProjectView project) {
        return (issue.type() == IssueType.STORY || issue.type() == IssueType.TASK) && issue.parentKey() == null && issue.workRole() == null
                && project.allows(IssueType.EPIC);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canSplit(String issueKey) {
        return issues.findByKey(issueKey).map(i -> i.type() == IssueType.EPIC || splittable(i, project(i.projectKey()))).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskInfo> epicTasks(String epicKey) {
        Issue epic = load(epicKey);
        ProjectView project = project(epic.projectKey());
        return issues.findChildren(epicKey).stream().filter(c -> c.workRole() != null).map(c -> taskInfo(c, project)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskInfo> taskInfo(String issueKey) {
        return issues.findByKey(issueKey).filter(i -> i.workRole() != null).map(i -> taskInfo(i, project(i.projectKey())));
    }

    private TaskInfo taskInfo(Issue task, ProjectView project) {
        return new TaskInfo(task.key(), task.title(), task.status(), columnName(project, task.status()), task.workRole(), task.dependsOn(),
                pendingDependencies(task, project), Optional.ofNullable(task.assigneeId()).flatMap(members::find).map(MemberView::name).orElse(null));
    }

    /** Tareas del plan que todavía esperan en la primera columna. */
    private List<Issue> waitingTasks(Issue epic, ProjectView project) {
        String first = project.columns().getFirst().key();
        return issues.findChildren(epic.key()).stream().filter(c -> c.workRole() != null && c.status().equals(first)).toList();
    }

    /** Etapa de trabajo: tiene roles y no es una puerta (p. ej. Desarrollo). */
    private static boolean workStage(ProjectView project, String column) {
        return project.stage(column).map(st -> !st.gate() && !st.roles().isEmpty()).orElse(false);
    }

    /** Donde se revisa el reparto: la columna de construcción si el flujo la tiene; si no, cualquier etapa de trabajo. */
    private boolean planStage(ProjectView project, String column) {
        return buildColumn(project).map(column::equals).orElse(workStage(project, column));
    }

    /**
     * La épica llegó a una etapa de trabajo con su reparto sin aprobar. Si la etapa tiene «el reparto arranca solo», las
     * tareas arrancan ya; si no, lo avisa y nada arranca hasta que una persona lo apruebe.
     */
    private void announcePendingPlan(Issue epic, ProjectView project, UUID actorId) {
        if (!planStage(project, epic.status())) return;
        List<Issue> waiting = waitingTasks(epic, project);
        if (waiting.isEmpty()) return;
        if (project.stage(epic.status()).map(StageDef::autoPlan).orElse(false)) {
            saveComment(epic, actorId, "**Reparto en marcha:** " + waiting.size() + " tarea(s) arrancan solas en «" + columnName(project, epic.status())
                    + "» (el tablero no espera la aprobación del reparto). Si algo no te convence, devuélvelas o pide cambios.", now());
            releaseTasks(epic, project, actorId);
            return;
        }
        saveComment(epic, actorId, "**Reparto por aprobar:** " + waiting.size() + " tarea(s) esperan en «" + columnName(project, project.columns().getFirst().key())
                + "» (" + String.join(", ", waiting.stream().map(Issue::key).toList()) + "). Revísalas y aprueba el reparto, o pide cambios al arquitecto.", now());
    }

    /**
     * Una persona revisa el reparto de tareas de la épica. Aprobar: las tareas arrancan en la etapa de la épica, cada una
     * para su rol. Pedir cambios: la épica vuelve a la etapa anterior (el diseño) con el motivo; el plan se rehace.
     */
    @Transactional
    public Issue reviewPlan(String epicKey, boolean approve, String comment, UUID actorId) {
        Issue epic = load(epicKey);
        ProjectView project = project(epic.projectKey());
        MemberView person = requireMember(actorId);
        if (person.agent()) throw new ForbiddenException("El reparto de tareas lo aprueba una persona, no un agente.");
        if (epic.type() != IssueType.EPIC) throw new DomainException(epicKey + " no es una épica.");
        List<Issue> waiting = waitingTasks(epic, project);
        if (waiting.isEmpty()) throw new DomainException(epicKey + " no tiene un reparto de tareas por aprobar.");
        Optional<String> build = buildColumn(project);
        boolean ready = build.map(b -> project.indexOf(epic.status()) >= project.indexOf(b) && !isDone(project, epic.status()))
                .orElse(workStage(project, epic.status()));
        if (!ready) {
            throw new DomainException("El reparto se aprueba cuando la épica llega a «" + build.map(b -> columnName(project, b)).orElse("una etapa de trabajo")
                    + "»; ahora está en «" + columnName(project, epic.status()) + "».");
        }
        if (approve) {
            saveComment(epic, actorId, "**Reparto aprobado** por " + person.name() + "." + (comment == null || comment.isBlank() ? "" : "\n\n" + comment.strip()), now());
            releaseTasks(epic, project, actorId);
            return load(epicKey);
        }
        if (comment == null || comment.isBlank()) throw new DomainException("Para pedir cambios al reparto, explica qué hay que cambiar.");
        String back = previousColumn(project, epic.status())
                .orElseThrow(() -> new DomainException("No hay a qué etapa devolver la épica: revisa las transiciones del flujo."));
        saveComment(epic, actorId, "**Cambios al reparto** pedidos por " + person.name() + ": " + comment.strip(), now());
        Issue moved = move(epicKey, back, Integer.MAX_VALUE, actorId);
        // Vuelve a quien hizo el reparto (el agente que creó las tareas), si esa etapa la cubre su rol.
        Optional<MemberView> planner = Optional.ofNullable(waiting.getFirst().reporterId()).flatMap(members::find).filter(MemberView::agent)
                .filter(a -> project.stage(back).map(st -> st.coveredBy(a.role())).orElse(false));
        if (planner.isPresent()) {
            Instant now = now();
            moved.assign(planner.get().id(), now);
            Issue saved = issues.save(moved);
            publishAssignmentIfChanged(saved, null, actorId, now);
            return saved;
        }
        return moved;
    }

    /**
     * Reparto aprobado: las tareas del plan arrancan donde se construye (Desarrollo), cada una para su rol, y la épica
     * espera ahí. Si la épica quedó más adelante (p. ej. la llevaron a QA antes de aprobar), vuelve a esa columna.
     */
    private void releaseTasks(Issue epic, ProjectView project, UUID actorId) {
        String first = project.columns().getFirst().key();
        List<Issue> waiting = waitingTasks(epic, project);
        if (waiting.isEmpty()) return;
        String target = buildColumn(project).orElse(epic.status());
        if (!target.equals(epic.status())) {
            String was = columnName(project, epic.status());
            place(epic, project, target, actorId);
            saveComment(epic, actorId, "La épica estaba en «" + was + "»: vuelve a «" + columnName(project, target)
                    + "», donde espera mientras se trabajan sus tareas. Sigue sola cuando terminen.", now());
        }
        Instant now = now();
        int position = issues.nextPosition(epic.projectKey(), target);
        ColumnDef to = requireColumn(project, target);
        String fromCategory = project.column(first).map(c -> c.category().name()).orElse(null);
        for (Issue task : waiting) {
            UUID previous = task.assigneeId();
            task.moveTo(target, now);
            task.reposition(position++);
            task.assign(null, now);
            if (task.supervisorId() == null) task.superviseBy(epic.supervisorId());
            issues.save(task);
            events.publishEvent(new IssueMoved(UUID.randomUUID(), TenantContext.require(), task.key(), task.projectKey(), actorId, now, first, target,
                    fromCategory, to.category().name()));
            publishAssignmentIfChanged(task, previous, actorId, now);
        }
        saveComment(epic, actorId, "Arrancan " + waiting.size() + " tarea(s) del plan en «" + to.name() + "»: "
                + String.join(", ", waiting.stream().map(Issue::key).toList()) + ". La épica se cierra cuando terminen todas.", now);
    }

    /**
     * Si todas las tareas del plan terminaron, la épica sigue: a la etapa de release si el flujo la tiene (una puerta justo
     * antes de terminados), si no, a terminados.
     */
    private void closeEpicIfDone(String epicKey, ProjectView project, UUID actorId) {
        Issue epic = issues.findByKey(epicKey).orElse(null);
        if (epic == null || isDone(project, epic.status())) return;
        List<Issue> tasks = issues.findChildren(epicKey).stream().filter(c -> c.workRole() != null).toList();
        if (tasks.isEmpty() || !tasks.stream().allMatch(t -> isDone(project, t.status()))) return;
        Optional<ColumnDef> next = releaseColumn(project).flatMap(project::column).or(() -> firstDone(project));
        if (next.isEmpty() || project.indexOf(epic.status()) >= project.indexOf(next.get().key())) return;
        place(epic, project, next.get().key(), actorId);
        saveComment(epic, actorId, "Terminaron todas sus tareas (" + tasks.size() + "): la épica pasa a «" + next.get().name() + "».", now());
    }

    /** Lleva el issue a la columna sin pasar por las transiciones del flujo (lo decide el propio flujo de tareas). */
    private void place(Issue issue, ProjectView project, String target, UUID actorId) {
        Instant now = now();
        String from = issue.status();
        String fromCategory = project.column(from).map(c -> c.category().name()).orElse(null);
        UUID previous = issue.assigneeId();
        issue.moveTo(target, now);
        issue.reposition(issues.nextPosition(issue.projectKey(), target));
        issue.assign(null, now);
        issues.save(issue);
        events.publishEvent(new IssueMoved(UUID.randomUUID(), TenantContext.require(), issue.key(), issue.projectKey(), actorId, now, from, target,
                fromCategory, requireColumn(project, target).category().name()));
        publishAssignmentIfChanged(issue, previous, actorId, now);
    }

    private static Optional<ColumnDef> firstDone(ProjectView project) {
        return project.columns().stream().filter(c -> c.category() == ec.kuador.board.catalog.ColumnCategory.DONE).findFirst();
    }

    /** La etapa de release: una puerta justo antes de la primera columna de terminados (p. ej. Release). */
    private static Optional<String> releaseColumn(ProjectView project) {
        return firstDone(project).flatMap(done -> {
            int i = project.indexOf(done.key());
            if (i <= 0) return Optional.empty();
            String before = project.columns().get(i - 1).key();
            return project.stage(before).filter(StageDef::gate).map(st -> before);
        });
    }

    private static boolean isDone(ProjectView project, String column) {
        return project.column(column).map(c -> c.category() == ec.kuador.board.catalog.ColumnCategory.DONE).orElse(false);
    }

    /**
     * Tarea de una épica en la columna donde se construye: la cubre su rol, no la etapa. Fuera de ella (QA, revisión…)
     * la atiende la etapa, como a cualquier tarjeta. En flujos sin etapa de código, vale la columna de su épica.
     */
    private boolean inWorkColumn(Issue issue) {
        if (issue.workRole() == null || issue.parentKey() == null) return false;
        Optional<String> build = projects.find(issue.projectKey()).flatMap(this::buildColumn);
        if (build.isPresent()) return build.get().equals(issue.status());
        return issues.findByKey(issue.parentKey()).map(p -> p.status().equals(issue.status())).orElse(false);
    }

    /**
     * La columna donde se construye: la primera etapa de trabajo que cubre un rol que escribe en el repo (en la fábrica,
     * Desarrollo). Ahí arrancan las tareas del reparto y ahí espera la épica mientras se trabajan.
     */
    Optional<String> buildColumn(ProjectView project) {
        if (!project.software()) return Optional.empty();
        for (ColumnDef c : project.columns()) {
            Optional<StageDef> st = project.stage(c.key());
            if (st.isPresent() && !st.get().gate() && st.get().roles().stream().anyMatch(this::writesCode)) return Optional.of(c.key());
        }
        return Optional.empty();
    }

    private boolean writesCode(String role) {
        return catalog.role(role).map(ec.kuador.board.catalog.AgentRole::worksOnCode).orElse(false);
    }

    /**
     * Una épica con tareas sin terminar no pasa de la columna de construcción: avanza sola cuando terminan (a Release o a
     * Hecho). Llevarla antes a QA dejaba sus tareas arrancando en QA, atendidas por el rol equivocado.
     */
    private void requireEpicWaitsForTasks(ProjectView project, Issue issue, String target) {
        if (issue.type() != IssueType.EPIC || issue.status().equals(target)) return;
        Optional<String> build = buildColumn(project);
        if (build.isEmpty() || project.indexOf(target) <= project.indexOf(build.get())) return;
        List<Issue> open = openTasks(issue, project);
        if (open.isEmpty()) return;
        String buildName = columnName(project, build.get());
        boolean waiting = !waitingTasks(issue, project).isEmpty();
        throw new DomainException(issue.key() + " tiene " + open.size() + " tarea(s) sin terminar: la épica espera en «" + buildName
                + "» y sigue sola cuando terminen." + (waiting ? " Primero aprueba el reparto (la épica debe estar en «" + buildName + "»)." : ""));
    }

    /** Tareas del plan de la épica que todavía no terminan. */
    private List<Issue> openTasks(Issue epic, ProjectView project) {
        return issues.findChildren(epic.key()).stream().filter(c -> c.workRole() != null && !isDone(project, c.status())).toList();
    }

    /** Dependencias que todavía no están listas para que otras tareas sigan. */
    private List<String> pendingDependencies(Issue issue, ProjectView project) {
        return issue.dependsOn().stream()
                .filter(k -> issues.findByKey(k).map(d -> !readyForDependents(project, d.status())).orElse(false))
                .toList();
    }

    /**
     * Una dependencia libera a las demás cuando llega a la última puerta del flujo (en software, Release: su PR ya pasó QA
     * y la revisión) o a una columna terminada. Esperar a «Hecho» encadenaba cada tarea al despliegue de la anterior.
     * Sin puertas, vale «Hecho».
     */
    static boolean readyForDependents(ProjectView project, String column) {
        if (isDone(project, column)) return true;
        List<String> order = project.columns().stream().map(ColumnDef::key).toList();
        int lastGate = -1;
        for (int i = 0; i < order.size(); i++) {
            if (project.stage(order.get(i)).map(StageDef::gate).orElse(false)) lastGate = i;
        }
        int at = order.indexOf(column);
        return lastGate >= 0 && at >= lastGate;
    }

    /**
     * Épica con reparto de tareas que nadie está trabajando: la trabajan sus tareas (o espera que una persona apruebe el
     * reparto). Si un agente la tiene (p. ej. el arquitecto rehaciendo el plan), sigue en su cola.
     */
    private boolean handledByTasks(Issue issue) {
        return issue.type() == IssueType.EPIC && issue.assigneeId() == null
                && issues.findChildren(issue.key()).stream().anyMatch(c -> c.workRole() != null);
    }

    // ---- Etapas: tomar, entregar y revisar en una puerta ----

    /** Toma un issue sin asignar de una etapa: solo quien tenga uno de sus roles responsables. */
    @Transactional
    public Issue take(String key, UUID actorId) {
        Issue issue = load(key);
        ProjectView project = project(issue.projectKey());
        MemberView actor = requireMember(actorId);
        if (issue.assigneeId() != null) {
            if (issue.assigneeId().equals(actorId)) return issue;
            String who = members.find(issue.assigneeId()).map(MemberView::name).orElse("otra persona");
            throw new ConflictException(issue.key() + " ya lo tomó " + who + ".");
        }
        List<String> pending = pendingDependencies(issue, project);
        if (!pending.isEmpty()) {
            throw new ConflictException(issue.key() + " depende de " + String.join(", ", pending) + ", que todavía no pasó su revisión (llega a la última puerta del flujo).");
        }
        Optional<StageDef> stage = project.stage(issue.status());
        if (inWorkColumn(issue)) {
            // Tarea de una épica en desarrollo: la toma quien tiene su rol.
            if (!actor.role().equals(issue.workRole()) && actor.agent()) {
                throw new ForbiddenException(issue.key() + " es una tarea de " + roleNames(List.of(issue.workRole())) + ".");
            }
        } else if (stage.isPresent()) {
            // En una puerta trabajan sus roles (p. ej. Release prepara) y la toman sus aprobadores.
            boolean preparesGate = stage.get().gate() && !stage.get().roles().isEmpty() && stage.get().roles().contains(actor.role());
            if (stage.get().gate() && !preparesGate) {
                requireApprover(project, stage.get(), actorId, issue);
            } else if (!stage.get().coveredBy(actor.role())) {
                throw new ForbiddenException("«" + columnName(project, issue.status()) + "» la cubren: " + roleNames(stage.get().roles()) + ".");
            }
        }
        Instant now = now();
        issue.assign(actorId, now);
        Issue saved = issues.save(issue);
        publishAssignmentIfChanged(issue, null, actorId, now);
        return saved;
    }

    /** Entrega el trabajo de la etapa: el issue pasa a la siguiente que permita el flujo y queda abierto allí. */
    @Transactional
    public Issue deliver(String key, String comment, UUID actorId) {
        return deliver(key, null, comment, actorId);
    }

    /** Entrega a {@code column} (o, si es nula, a la siguiente etapa permitida); debe estar más adelante en el flujo. */
    private Issue deliver(String key, String column, String comment, UUID actorId) {
        Issue issue = load(key);
        ProjectView project = project(issue.projectKey());
        MemberView actor = requireMember(actorId);
        Optional<StageDef> stage = project.stage(issue.status());
        if (stage.isPresent() && stage.get().gate()) {
            throw new DomainException("«" + columnName(project, issue.status()) + "» es una puerta: se aprueba o se rechaza.");
        }
        if (actor.agent() && !actorId.equals(issue.assigneeId())) {
            throw new ForbiddenException("Un agente solo entrega los issues que tomó.");
        }
        ColumnDef next = column != null ? requireColumn(project, column) : project.nextAfter(issue.status())
                .orElseThrow(() -> new DomainException("Desde «" + columnName(project, issue.status()) + "» no hay una etapa siguiente permitida."));
        if (column != null && project.indexOf(next.key()) <= project.indexOf(issue.status())) {
            throw new DomainException("«" + next.name() + "» no está después de «" + columnName(project, issue.status()) + "».");
        }
        if (comment != null && !comment.isBlank()) saveComment(issue, actorId, comment.strip(), now());
        Issue moved = move(key, next.key(), Integer.MAX_VALUE, actorId);
        if (actor.agent() && actorId.equals(moved.assigneeId())) {
            // La columna siguiente no tiene etapa: ningún rol la cubre, así que el agente no se queda con el
            // issue (volvería a sus pendientes). Pasa a la persona que lo supervisa, que decide qué sigue.
            Instant now = now();
            moved.assign(moved.supervisorId(), now);
            Issue saved = issues.save(moved);
            publishAssignmentIfChanged(saved, actorId, actorId, now);
            return saved;
        }
        return moved;
    }

    /** Cierra una puerta: aprobar pasa a la siguiente etapa; rechazar devuelve el issue con un motivo. */
    @Transactional
    public Issue review(String key, boolean approve, String comment, UUID actorId) {
        Issue issue = load(key);
        ProjectView project = project(issue.projectKey());
        StageDef gate = project.stage(issue.status()).filter(StageDef::gate)
                .orElseThrow(() -> new DomainException("«" + columnName(project, issue.status()) + "» no es una puerta de aprobación."));
        MemberView person = requireApprover(project, gate, actorId, issue);
        String target;
        if (approve) {
            target = project.nextAfter(issue.status()).map(ColumnDef::key)
                    .orElseThrow(() -> new DomainException("Desde «" + columnName(project, issue.status()) + "» no hay una etapa siguiente permitida."));
            saveComment(issue, actorId, "**Aprobado** por " + person.name() + "." + (comment == null || comment.isBlank() ? "" : "\n\n" + comment.strip()), now());
        } else {
            if (comment == null || comment.isBlank()) throw new DomainException("Para rechazar, explica qué hay que corregir.");
            target = gate.rejectTo() != null ? gate.rejectTo() : previousColumn(project, issue.status())
                    .orElseThrow(() -> new DomainException("No hay a qué etapa devolver el issue: configura «Rechazar a» en la puerta."));
            saveComment(issue, actorId, "**Rechazado** por " + person.name() + ": " + comment.strip(), now());
        }
        return move(key, target, Integer.MAX_VALUE, actorId);
    }

    // ---- Mis decisiones ----

    /**
     * Algo que espera la decisión de una persona. {@code kind}: GATE (una puerta que aprueba), PLAN (el reparto de una
     * épica) o HANDED (un agente le dejó la tarjeta: una pregunta, un escalamiento o un trabajo devuelto).
     */
    public record Decision(String kind, String issueKey, String projectKey, String projectName, String title, String column,
            String columnName, String detail, String from, Instant since) {}

    /** Lo que espera a esta persona en los tableros que ve, lo más antiguo primero. */
    @Transactional(readOnly = true)
    public List<Decision> decisionsFor(UUID memberId) {
        MemberView me = requireMember(memberId);
        if (me.agent()) return List.of();
        List<Decision> out = new ArrayList<>();
        for (String key : projects.visibleKeys()) {
            ProjectView project = project(key);
            String projectName = project.name();
            // Puertas que aprueba: sin un agente revisándolas y sin otra persona que ya la tenga.
            for (StageDef gate : project.stages()) {
                if (!gate.gate() || !gate.approvableBy(me.role())) continue;
                for (Issue i : issues.findColumn(key, gate.column())) {
                    UUID assignee = i.assigneeId();
                    if (assignee != null && !assignee.equals(memberId)) continue;
                    if (assignee == null && agentReviewed(i, project)) continue;
                    out.add(decision("GATE", i, project, projectName, lastComment(i).orElse(null)));
                }
            }
            // Repartos de épicas por aprobar: los ve quien la supervisa (o quien administra, si nadie la supervisa).
            for (Issue epic : issues.findByProject(key, IssueFilter.none())) {
                if (epic.type() != IssueType.EPIC || !workStage(project, epic.status()) || waitingTasks(epic, project).isEmpty()) continue;
                boolean mine = memberId.equals(epic.supervisorId()) || (epic.supervisorId() == null && me.administers());
                if (mine) out.add(decision("PLAN", epic, project, projectName, lastComment(epic).orElse(null)));
            }
            // Tarjetas que un agente le dejó: la tiene asignada y lo último lo escribió un agente.
            for (Issue i : issues.findByProject(key, new IssueFilter(null, memberId, null))) {
                if (!memberId.equals(i.assigneeId()) || isDone(project, i.status()) || project.stage(i.status()).map(StageDef::gate).orElse(false)) continue;
                Optional<Comment> last = lastComment(i);
                if (last.isPresent() && members.find(last.get().authorId()).map(MemberView::agent).orElse(false)) {
                    out.add(decision("HANDED", i, project, projectName, last.get()));
                }
            }
        }
        out.sort(java.util.Comparator.comparing(Decision::since, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return out;
    }

    private Optional<Comment> lastComment(Issue issue) {
        List<Comment> all = issues.findComments(issue);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.getLast());
    }

    private Decision decision(String kind, Issue i, ProjectView project, String projectName, Comment last) {
        String from = last == null ? null : members.find(last.authorId()).map(MemberView::name).orElse(null);
        String detail = last == null ? null : excerpt(last.body());
        return new Decision(kind, i.key(), project.key(), projectName, i.title(), i.status(), columnName(project, i.status()), detail, from,
                last == null ? i.updatedAt() : last.createdAt());
    }

    private static String excerpt(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 280 ? flat : flat.substring(0, 277) + "…";
    }

    // ---- Puertas que revisa un agente ----

    /** La tarjeta está en una puerta que, para ella, revisa un agente, y hay un agente con ese rol en el espacio. */
    @Override
    @Transactional(readOnly = true)
    public boolean agentReviews(String issueKey) {
        return issues.findByKey(issueKey).map(i -> agentReviewed(i, project(i.projectKey()))).orElse(false);
    }

    private boolean agentReviewed(Issue i, ProjectView project) {
        Optional<StageDef> stage = project.stage(i.status());
        if (stage.isEmpty() || !stage.get().gate() || stage.get().reviewFor(i.reviewBy()) == StageDef.ReviewMode.PERSON) return false;
        String role = stage.get().reviewerRole();
        return members.agents().stream().anyMatch(a -> a.role().equals(role));
    }

    /** Quién revisa esta tarjeta en las puertas: PERSON, AGENT o nulo (lo del tablero). Personas con permiso de edición. */
    @Transactional
    public Issue setReviewBy(String key, String reviewBy, UUID actorId) {
        Issue issue = load(key);
        MemberView actor = requireMember(actorId);
        if (actor.agent()) throw new ForbiddenException("Quién revisa una tarjeta lo decide una persona.");
        if ("AGENT".equals(reviewBy)) {
            // Dejarla al agente salta a quienes aprueban: solo lo decide alguien que podría aprobar esas puertas.
            ProjectView board = project(issue.projectKey());
            for (StageDef gate : board.stages()) {
                if (gate.gate() && gate.reviewFor(null) == StageDef.ReviewMode.PERSON && !gate.approvableBy(actor.role())) {
                    throw new ForbiddenException("Solo quien aprueba «" + columnName(board, gate.column()) + "» puede dejar esta tarjeta al agente revisor ("
                            + roleNames(gate.approvers()) + ").");
                }
            }
        }
        String before = issue.reviewBy();
        issue.reviewBy(reviewBy);
        if (java.util.Objects.equals(before, issue.reviewBy())) return issue;
        Instant now = now();
        // Ya en una puerta y ahora la revisa una persona: el agente revisor la suelta.
        ProjectView project = project(issue.projectKey());
        UUID previousAssignee = issue.assigneeId();
        project.stage(issue.status()).filter(StageDef::gate).ifPresent(gate -> {
            boolean byAgent = gate.reviewFor(issue.reviewBy()) != StageDef.ReviewMode.PERSON;
            if (!byAgent && Optional.ofNullable(issue.assigneeId()).flatMap(members::find).map(MemberView::agent).orElse(false)) issue.assign(null, now);
        });
        Issue saved = issues.save(issue);
        // La cadena de agentes lo oye: si ahora la revisa el agente y está libre en la puerta, se la asigna.
        events.publishEvent(new IssueUpdated(UUID.randomUUID(), TenantContext.require(), key, saved.projectKey(), actorId, now,
                List.of(new IssueUpdated.Change("reviewBy", before, saved.reviewBy()))));
        publishAssignmentIfChanged(saved, previousAssignee, actorId, now);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canApproveGate(String issueKey, UUID actorId) {
        Optional<Issue> found = issues.findByKey(issueKey);
        Optional<MemberView> actor = members.find(actorId);
        if (found.isEmpty() || actor.isEmpty() || actor.get().agent()) return false;
        return project(found.get().projectKey()).stage(found.get().status()).filter(StageDef::gate)
                .map(g -> g.approvableBy(actor.get().role())).orElse(false);
    }

    @Override
    @Transactional
    public Progress approveGate(String issueKey, String comment, UUID actorId) {
        return progress(review(issueKey, true, comment, actorId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GateReview> gateReview(String issueKey, UUID agentId) {
        Optional<Issue> found = issues.findByKey(issueKey);
        Optional<MemberView> agent = members.find(agentId).filter(MemberView::agent);
        if (found.isEmpty() || agent.isEmpty()) return Optional.empty();
        Issue issue = found.get();
        ProjectView project = project(issue.projectKey());
        Optional<StageDef> stage = project.stage(issue.status()).filter(StageDef::gate);
        if (stage.isEmpty()) return Optional.empty();
        StageDef.ReviewMode mode = stage.get().reviewFor(issue.reviewBy());
        if (mode == StageDef.ReviewMode.PERSON || !agent.get().role().equals(stage.get().reviewerRole())) return Optional.empty();
        String back = stage.get().rejectTo() != null ? stage.get().rejectTo() : previousColumn(project, issue.status()).orElse(null);
        String next = project.nextAfter(issue.status()).map(ColumnDef::key).orElse(null);
        return Optional.of(new GateReview(mode.name(), columnName(project, issue.status()), back == null ? null : columnName(project, back),
                next == null ? null : columnName(project, next), stage.get().agentReview().sensitive(), stage.get().agentReview().maxChangedLines(),
                approverNames(project, stage.get()), stage.get().review() != null && stage.get().review().merges()));
    }

    @Override
    @Transactional
    public Progress reviewAs(String issueKey, boolean approve, String comment, UUID agentId) {
        return progress(review(issueKey, approve, comment, agentId));
    }

    /** El agente revisor no puede decidir: comenta su análisis y deja la tarjeta a una persona que aprueba la puerta. */
    @Override
    @Transactional
    public Progress escalate(String issueKey, String analysis, UUID agentId) {
        Issue issue = load(issueKey);
        ProjectView project = project(issue.projectKey());
        StageDef gate = project.stage(issue.status()).filter(StageDef::gate)
                .orElseThrow(() -> new DomainException("«" + columnName(project, issue.status()) + "» no es una puerta de aprobación."));
        Instant now = now();
        MemberView person = approverFor(issue, gate);
        saveComment(issue, agentId, "🔎 **Revisión para una persona** — " + person.name() + ", esta la decides tú:\n\n" + analysis.strip(), now);
        UUID before = issue.assigneeId();
        issue.assign(person.id(), now);
        Issue saved = issues.save(issue);
        publishAssignmentIfChanged(saved, before, agentId, now);
        return progress(saved);
    }

    /** Quien decide una puerta escalada: quien supervisa la tarjeta si puede aprobarla; si no, una persona aprobadora. */
    private MemberView approverFor(Issue issue, StageDef gate) {
        return Optional.ofNullable(issue.supervisorId()).flatMap(members::find).filter(m -> !m.agent() && gate.approvableBy(m.role()))
                .or(() -> members.humans().stream().filter(m -> gate.approvableBy(m.role()) && "OWNER".equals(m.role())).findFirst())
                .or(() -> members.humans().stream().filter(m -> gate.approvableBy(m.role())).findFirst())
                .or(() -> responsibleHuman(issue))
                .orElseThrow(() -> new DomainException("No hay una persona que pueda aprobar esta puerta."));
    }

    private String approverNames(ProjectView project, StageDef gate) {
        return gate.approvers().isEmpty() ? "cualquier persona del equipo" : roleNames(gate.approvers());
    }

    // ---- Supervisión: qué persona pone a trabajar a los agentes ----

    /**
     * Cuando una persona le pasa trabajo a los agentes (lo asigna a un agente o lo lleva a una etapa que
     * cubren agentes), pasa a supervisarlo: solo sus agentes, en su cliente de IA, lo atienden. Lo que
     * hacen los agentes no cambia quién supervisa: se hereda por toda la cadena.
     */
    private void superviseIfPersonHandsToAgents(Issue issue, ProjectView project, UUID actorId) {
        if (actorId == null) return;
        Optional<MemberView> actor = members.find(actorId);
        if (actor.isEmpty() || actor.get().agent()) return;
        boolean toAgent = issue.assigneeId() != null && members.find(issue.assigneeId()).map(MemberView::agent).orElse(false);
        if (toAgent || (issue.assigneeId() == null && !agentRolesOf(project.stage(issue.status())).isEmpty())) {
            issue.superviseBy(actorId);
        }
    }

    /** Roles de la etapa que cubre algún agente del espacio. */
    private List<String> agentRolesOf(Optional<StageDef> stage) {
        if (stage.isEmpty()) return List.of();
        List<String> agentRoles = members.agents().stream().map(MemberView::role).toList();
        return stage.get().roles().stream().filter(agentRoles::contains).toList();
    }

    /** Una persona se encarga de que sus agentes atiendan el issue (p. ej. si lo dejó otra persona). */
    @Transactional
    public Issue supervise(String key, UUID actorId) {
        Issue issue = load(key);
        MemberView actor = requireMember(actorId);
        if (actor.agent()) throw new ForbiddenException("Solo una persona supervisa el trabajo de los agentes.");
        issue.superviseBy(actorId);
        return issues.save(issue);
    }

    @Override
    @Transactional
    public void handOver(String issueKey, UUID personId) {
        Issue issue = load(issueKey);
        if (personId.equals(issue.supervisorId())) return;
        supervise(issueKey, personId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> supervisorOf(String issueKey) {
        return issues.findByKey(issueKey).map(Issue::supervisorId);
    }

    /**
     * Cola de los agentes de una persona: los issues que supervisa y que esperan a un agente, ya sea
     * asignados a uno o sin tomar en una etapa que cubren agentes. No incluye lo terminado ni las
     * puertas que esperan a una persona.
     */
    @Override
    @Transactional(readOnly = true)
    public List<QueueItem> agentQueue(UUID supervisorId) {
        java.util.Map<String, Optional<ProjectView>> byKey = new java.util.HashMap<>();
        List<QueueItem> queue = new ArrayList<>();
        for (Issue i : issues.findBySupervisor(supervisorId)) {
            ProjectView project = byKey.computeIfAbsent(i.projectKey(), projects::find).orElse(null);
            if (project != null) queueItem(i, project).ifPresent(queue::add);
        }
        return queue;
    }

    @Override
    @Transactional(readOnly = true)
    public List<QueueItem> openForAgents(String projectKey) {
        ProjectView project = projects.find(projectKey).orElse(null);
        if (project == null) return List.of();
        return issues.findByProject(projectKey, IssueFilter.none()).stream()
                .filter(i -> i.assigneeId() == null)
                .flatMap(i -> queueItem(i, project).filter(q -> !q.gate() || agentReviewed(i, project)).stream())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<QueueItem> waitingForAgents(String projectKey) {
        ProjectView project = projects.find(projectKey).orElse(null);
        if (project == null) return List.of();
        return issues.findByProject(projectKey, IssueFilter.none()).stream()
                .flatMap(i -> queueItem(i, project).filter(q -> !q.gate() || agentReviewed(i, project)).stream())
                .toList();
    }

    /** El issue espera a un agente (asignado a uno o por tomar en una etapa que cubren agentes); si no, vacío. */
    private Optional<QueueItem> queueItem(Issue i, ProjectView project) {
        Optional<ColumnDef> column = project.column(i.status());
        if (column.isEmpty() || column.get().category() == ec.kuador.board.catalog.ColumnCategory.DONE) return Optional.empty();
        Optional<StageDef> stage = project.stage(i.status());
        Optional<MemberView> assignee = Optional.ofNullable(i.assigneeId()).flatMap(members::find);
        if (handledByTasks(i)) return Optional.empty(); // la trabajan sus tareas, o espera la aprobación del reparto
        if (!pendingDependencies(i, project).isEmpty()) return Optional.empty(); // espera a otras tareas
        List<String> roles;
        if (assignee.isPresent()) {
            if (!assignee.get().agent()) return Optional.empty();
            roles = List.of(assignee.get().role());
        } else if (inWorkColumn(i)) {
            if (members.agents().stream().noneMatch(a -> a.role().equals(i.workRole()))) return Optional.empty(); // la hace una persona
            roles = List.of(i.workRole());
        } else if (agentReviewed(i, project)) {
            // Puerta que revisa un agente: la toma su agente revisor.
            roles = List.of(stage.get().reviewerRole());
        } else {
            // Una tarea del plan que aún no arranca (sigue en la primera columna) no es de nadie todavía.
            if (i.workRole() != null && i.status().equals(project.columns().getFirst().key())) return Optional.empty();
            roles = agentRolesOf(stage);
            if (roles.isEmpty()) return Optional.empty();
        }
        return Optional.of(new QueueItem(i.key(), i.projectKey(), i.title(), i.type().name(), i.priority().name(), i.status(), column.get().name(),
                stage.map(StageDef::gate).orElse(false), roles, assignee.map(MemberView::id).orElse(null),
                assignee.map(MemberView::name).orElse(null), i.reworkCount()));
    }

    @Override
    @Transactional
    public Progress takeAs(String issueKey, UUID agentId) {
        return progress(take(issueKey, agentId));
    }

    @Override
    @Transactional
    public Progress deliverAs(String issueKey, String comment, UUID agentId) {
        return progress(deliver(issueKey, comment, agentId));
    }

    @Override
    @Transactional
    public Progress deliverTo(String issueKey, String column, String comment, UUID agentId) {
        return progress(deliver(issueKey, column, comment, agentId));
    }

    /** Devuelve el issue a una etapa anterior con el motivo (p. ej. QA encontró fallas): suma una vuelta atrás. */
    @Override
    @Transactional
    public Progress sendBack(String issueKey, String column, String reason, UUID actorId) {
        Issue issue = load(issueKey);
        ProjectView project = project(issue.projectKey());
        MemberView actor = requireMember(actorId);
        ColumnDef to = requireColumn(project, column);
        if (project.indexOf(to.key()) >= project.indexOf(issue.status())) {
            throw new DomainException("«" + to.name() + "» no está antes de «" + columnName(project, issue.status()) + "»: para avanzar, entrega el issue.");
        }
        if (actor.agent() && !actorId.equals(issue.assigneeId())) {
            throw new ForbiddenException("Un agente solo devuelve los issues que tomó.");
        }
        if (reason == null || reason.isBlank()) throw new DomainException("Para devolver un issue, explica qué hay que corregir.");
        saveComment(issue, actorId, "**Devuelto a " + to.name() + "** por " + actor.name() + ": " + reason.strip(), now());
        return progress(move(issueKey, to.key(), Integer.MAX_VALUE, actorId));
    }

    @Override
    @Transactional
    public Optional<String> returnForConflict(String issueKey, String note, UUID actorId) {
        Optional<Issue> found = issues.findByKey(issueKey);
        if (found.isEmpty() || note == null || note.isBlank()) return Optional.empty();
        Issue issue = found.get();
        ProjectView project = project(issue.projectKey());
        if (project.column(issue.status()).map(c -> c.category() == ec.kuador.board.catalog.ColumnCategory.DONE).orElse(true)) return Optional.empty();
        String body = "⚠️ **Conflicto con la rama destino**: " + note.strip();
        if (lastComment(issue).map(c -> c.body().endsWith(body)).orElse(false)) return Optional.empty();
        Optional<StageDef> gate = project.stage(issue.status()).filter(StageDef::gate);
        Optional<String> back = gate.flatMap(g -> Optional.ofNullable(g.rejectTo()).or(() -> previousColumn(project, issue.status())));
        if (back.isEmpty()) {
            saveComment(issue, actorId, body, now());
            return Optional.empty();
        }
        saveComment(issue, actorId, "**Devuelto a " + columnName(project, back.get()) + "**. " + body, now());
        move(issueKey, back.get(), Integer.MAX_VALUE, actorId);
        return back.map(k -> columnName(project, k));
    }

    private Progress progress(Issue issue) {
        String name = projects.find(issue.projectKey()).map(p -> columnName(p, issue.status())).orElse(issue.status());
        return new Progress(issue.key(), issue.status(), name, issue.assigneeId());
    }

    /**
     * Cierra una puerta una persona (con uno de sus roles aprobadores, si los pide) o, si la puerta está en revisión por
     * agente para esta tarjeta, su agente revisor.
     */
    /**
     * Las puertas que quedan entre {@code from} (exclusivo; {@code null} = antes de la primera columna) y {@code target}
     * (exclusivo), hacia adelante: pasar por encima de una exige poder aprobarla.
     */
    private void requireGatesCrossed(ProjectView project, String from, String target, UUID actorId, Issue issue) {
        List<String> order = project.columns().stream().map(ColumnDef::key).toList();
        int fromIndex = from == null ? -1 : order.indexOf(from);
        int toIndex = order.indexOf(target);
        if (toIndex <= fromIndex + 1) return;
        for (int i = fromIndex + 1; i < toIndex; i++) {
            Optional<StageDef> gate = project.stage(order.get(i)).filter(StageDef::gate);
            if (gate.isPresent()) requireApprover(project, gate.get(), actorId, issue);
        }
    }

    private MemberView requireApprover(ProjectView project, StageDef gate, UUID actorId, Issue issue) {
        MemberView actor = requireMember(actorId);
        String gateName = columnName(project, gate.column());
        if (actor.agent()) {
            if (gate.reviewFor(issue.reviewBy()) != StageDef.ReviewMode.PERSON && actor.role().equals(gate.reviewerRole())) return actor;
            throw new ForbiddenException("«" + gateName + "» es una puerta: la cierra una persona, no un agente.");
        }
        if (!gate.approvableBy(actor.role())) {
            throw new ForbiddenException("«" + gateName + "» la aprueba: " + roleNames(gate.approvers()) + ".");
        }
        return actor;
    }

    /** La persona responsable de un issue: quien lo reportó; si fue un agente, la dueña del espacio. */
    private Optional<MemberView> responsibleHuman(Issue issue) {
        return Optional.ofNullable(issue.reporterId()).flatMap(members::find).filter(m -> !m.agent())
                .or(() -> members.humans().stream().filter(m -> "OWNER".equals(m.role())).findFirst())
                .or(() -> members.humans().stream().findFirst());
    }

    private static Optional<String> previousColumn(ProjectView project, String from) {
        int i = project.indexOf(from);
        for (int j = Math.min(i, project.columns().size()) - 1; j >= 0; j--) {
            String k = project.columns().get(j).key();
            if (project.canMove(from, k)) return Optional.of(k);
        }
        return Optional.empty();
    }

    private Comment saveComment(Issue issue, UUID authorId, String body, Instant at) {
        Comment comment = issues.saveComment(Comment.create(issue.id(), authorId, body, at));
        String excerpt = comment.body().length() > EXCERPT ? comment.body().substring(0, EXCERPT) + "…" : comment.body();
        events.publishEvent(new IssueCommented(UUID.randomUUID(), TenantContext.require(), issue.key(), issue.projectKey(), authorId, at,
                comment.id(), excerpt));
        return comment;
    }

    private static String columnName(ProjectView project, String key) {
        return project.column(key).map(ColumnDef::name).orElse(key);
    }

    /** Nombres legibles de roles: los del catálogo; «Dueño del espacio» para OWNER. */
    private String roleNames(List<String> keys) {
        return String.join(", ", keys.stream().map(k -> "OWNER".equals(k) ? "Dueño del espacio"
                : catalog.role(k).map(r -> r.name()).orElse(k)).toList());
    }

    @Transactional
    public void delete(String key, UUID actorId) {
        Issue issue = load(key);
        issues.delete(issue);
        events.publishEvent(new IssueDeleted(UUID.randomUUID(), TenantContext.require(), key, issue.projectKey(), actorId, now(), issue.title()));
    }

    @Transactional(readOnly = true)
    public List<Comment> comments(String key) {
        return issues.findComments(load(key));
    }

    @Transactional
    public Comment comment(String key, UUID authorId, String body) {
        Issue issue = load(key);
        requireMember(authorId);
        return saveComment(issue, authorId, body == null ? "" : body, now());
    }

    /** Su autor corrige el comentario (p. ej. lo acorta para que un agente lea menos). */
    @Transactional
    public Comment editComment(String key, UUID commentId, UUID actorId, String body) {
        Comment c = ownComment(key, commentId);
        if (!c.authorId().equals(actorId)) throw new ForbiddenException("Solo quien escribió el comentario puede editarlo.");
        return issues.saveComment(c.edited(body, now()));
    }

    /** Lo borra su autor o quien administra el espacio. */
    @Transactional
    public void deleteComment(String key, UUID commentId, UUID actorId) {
        Comment c = ownComment(key, commentId);
        boolean admin = members.find(actorId).map(MemberView::administers).orElse(false);
        if (!c.authorId().equals(actorId) && !admin) throw new ForbiddenException("Solo quien escribió el comentario (o quien administra) puede borrarlo.");
        issues.deleteComment(c.id());
    }

    /** El comentario, si es de ese issue. */
    private Comment ownComment(String key, UUID commentId) {
        Issue issue = load(key);
        return issues.findComment(commentId).filter(c -> c.issueId().equals(issue.id()))
                .orElseThrow(() -> new NotFoundException("No existe ese comentario en " + issue.key() + "."));
    }

    private void publishAssignmentIfChanged(Issue issue, UUID previous, UUID actorId, Instant now) {
        if (!Objects.equals(previous, issue.assigneeId())) {
            events.publishEvent(new IssueAssigned(UUID.randomUUID(), TenantContext.require(), issue.key(), issue.projectKey(), actorId, now,
                    previous, issue.assigneeId()));
        }
    }

    private ProjectView project(String projectKey) {
        return projects.find(projectKey).orElseThrow(() -> new NotFoundException("No existe el proyecto " + projectKey + "."));
    }

    private static ColumnDef requireColumn(ProjectView project, String columnKey) {
        return project.column(columnKey).orElseThrow(() -> new DomainException(
                "La columna " + columnKey + " no existe en el flujo de " + project.key() + "."));
    }

    /** {@code currentAssignee}: quien ya lo tenía (puede seguir aunque haya salido del tablero). */
    private void validateReferences(ProjectView project, IssueDetails d, String selfKey, UUID currentAssignee) {
        String projectKey = project.key();
        if (d.type() != null && !project.allows(d.type())) {
            throw new DomainException("Este proyecto no usa el tipo " + d.type() + "; usa "
                    + project.issueTypes().stream().map(t -> t.label() + " (" + t.type() + ")").toList() + ".");
        }
        if (d.assigneeId() != null && !d.assigneeId().equals(currentAssignee)) {
            requireOnBoard(d.assigneeId(), projectKey);
        }
        if (d.parentKey() != null) {
            Issue parent = issues.findByKey(d.parentKey())
                    .orElseThrow(() -> new DomainException("El issue padre " + d.parentKey() + " no existe."));
            if (!parent.projectKey().equals(projectKey)) {
                throw new DomainException("El padre debe ser del mismo proyecto.");
            }
            if (parent.type() != IssueType.EPIC) {
                throw new DomainException("Solo una épica puede ser padre de otros issues.");
            }
            if (parent.key().equals(selfKey)) {
                throw new DomainException("Un issue no puede ser su propio padre.");
            }
        }
    }

    /** El miembro existe y ve el tablero: no se le pasa trabajo a quien no está en él. */
    private MemberView requireOnBoard(UUID memberId, String projectKey) {
        MemberView m = requireMember(memberId);
        if (!projects.canAccess(m.id(), projectKey)) throw new DomainException(m.name() + " no está en el tablero " + projectKey + ".");
        return m;
    }

    private MemberView requireMember(UUID memberId) {
        return members.find(memberId).orElseThrow(() -> new DomainException("El miembro " + memberId + " no existe."));
    }

    private Issue load(String key) {
        return issues.findByKey(key).orElseThrow(() -> new NotFoundException("No existe el issue " + key + "."));
    }

    private Instant now() {
        return clock.instant();
    }
}
