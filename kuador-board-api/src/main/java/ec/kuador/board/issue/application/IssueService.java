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
    @Transactional(readOnly = true)
    public Optional<IssueRef> find(String issueKey) {
        return issues.findByKey(issueKey).map(i -> new IssueRef(i.key(), i.projectKey(),
                Integer.parseInt(i.key().substring(i.key().lastIndexOf('-') + 1)), i.title()));
    }

    @Transactional
    public Issue create(String projectKey, IssueDetails details, UUID actorId, String status) {
        ProjectView project = project(projectKey);
        validateReferences(project, details, null);
        if (actorId != null) {
            requireMember(actorId);
        }
        // Sin columna indicada, el issue entra en la primera del flujo.
        String column = status == null || status.isBlank() ? project.columns().getFirst().key() : requireColumn(project, status).key();
        int number = projects.allocateIssueNumber(projectKey);
        Instant now = now();
        Issue created = Issue.create(projectKey, number, details, actorId, column, issues.nextPosition(projectKey, column), now);
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
        validateReferences(project(issue.projectKey()), details, issue.key());
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
            requireMember(memberId);
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
            requireApprover(project, fromStage.get(), actorId);
        }
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
        }
        return issue;
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
        Optional<StageDef> stage = project.stage(issue.status());
        if (stage.isPresent()) {
            // En una puerta trabajan sus roles (p. ej. Release prepara) y la toman sus aprobadores.
            boolean preparesGate = stage.get().gate() && !stage.get().roles().isEmpty() && stage.get().roles().contains(actor.role());
            if (stage.get().gate() && !preparesGate) {
                requireApprover(project, stage.get(), actorId);
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
        ColumnDef next = project.nextAfter(issue.status())
                .orElseThrow(() -> new DomainException("Desde «" + columnName(project, issue.status()) + "» no hay una etapa siguiente permitida."));
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
        MemberView person = requireApprover(project, gate, actorId);
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
            if (project == null) continue;
            Optional<ColumnDef> column = project.column(i.status());
            if (column.isEmpty() || column.get().category() == ec.kuador.board.catalog.ColumnCategory.DONE) continue;
            Optional<StageDef> stage = project.stage(i.status());
            Optional<MemberView> assignee = Optional.ofNullable(i.assigneeId()).flatMap(members::find);
            List<String> roles;
            if (assignee.isPresent()) {
                if (!assignee.get().agent()) continue;
                roles = List.of(assignee.get().role());
            } else {
                roles = agentRolesOf(stage);
                if (roles.isEmpty()) continue;
            }
            queue.add(new QueueItem(i.key(), i.projectKey(), i.title(), i.type().name(), i.priority().name(), i.status(), column.get().name(),
                    stage.map(StageDef::gate).orElse(false), roles, assignee.map(MemberView::id).orElse(null),
                    assignee.map(MemberView::name).orElse(null), i.reworkCount()));
        }
        return queue;
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

    private Progress progress(Issue issue) {
        String name = projects.find(issue.projectKey()).map(p -> columnName(p, issue.status())).orElse(issue.status());
        return new Progress(issue.key(), issue.status(), name, issue.assigneeId());
    }

    /** Solo una persona cierra una puerta, y si la puerta pide roles, con uno de ellos. */
    private MemberView requireApprover(ProjectView project, StageDef gate, UUID actorId) {
        MemberView actor = requireMember(actorId);
        String gateName = columnName(project, gate.column());
        if (actor.agent()) {
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

    private void validateReferences(ProjectView project, IssueDetails d, String selfKey) {
        String projectKey = project.key();
        if (d.type() != null && !project.allows(d.type())) {
            throw new DomainException("Este proyecto no usa el tipo " + d.type() + "; usa "
                    + project.issueTypes().stream().map(t -> t.label() + " (" + t.type() + ")").toList() + ".");
        }
        if (d.assigneeId() != null) {
            requireMember(d.assigneeId());
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
