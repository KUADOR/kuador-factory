package ec.kuador.board.issue.application;

import static ec.kuador.board.issue.application.Flow.*;

import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.IssueApi.TaskInfo;
import ec.kuador.board.issue.IssueMoved;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Las tareas del reparto de una épica: cuándo esperan, cuándo arrancan y cuándo dejan seguir a la épica. */
final class EpicTasks extends IssueSupport {

    EpicTasks(Ports ports) {
        super(ports);
    }

    /**
     * La épica llegó a la etapa de construcción antes de tener tareas (p. ej. la movieron mientras el arquitecto aún
     * repartía) y otro agente la tomó como si fuera una tarjeta más. Ya repartida, la trabajan sus tareas: queda libre, el
     * agente deja de trabajarla y el reparto espera la aprobación de una persona.
     */
    void releaseEpicFromAgent(Issue epic, UUID actorId) {
        UUID holder = epic.assigneeId();
        if (holder == null || holder.equals(actorId) || members.find(holder).map(m -> !m.agent()).orElse(true)) return;
        Instant now = now();
        Issue current = load(epic.key());
        current.assign(null, now);
        issues.save(current);
        publishAssignmentIfChanged(current, holder, actorId, now);
    }

    /** Una historia o tarea suelta (sin épica ni rol de reparto) que el plan puede repartir: pasa a ser épica. */
    boolean splittable(Issue issue, ProjectView project) {
        return (issue.type() == IssueType.STORY || issue.type() == IssueType.TASK) && issue.parentKey() == null && issue.workRole() == null
                && project.allows(IssueType.EPIC);
    }

    boolean canSplit(String issueKey) {
        return issues.findByKey(issueKey).map(i -> i.type() == IssueType.EPIC || splittable(i, project(i.projectKey()))).orElse(false);
    }

    List<TaskInfo> epicTasks(String epicKey) {
        Issue epic = load(epicKey);
        ProjectView project = project(epic.projectKey());
        return issues.findChildren(epicKey).stream().filter(c -> c.workRole() != null).map(c -> taskInfo(c, project)).toList();
    }

    Optional<TaskInfo> taskInfo(String issueKey) {
        return issues.findByKey(issueKey).filter(i -> i.workRole() != null).map(i -> taskInfo(i, project(i.projectKey())));
    }

    TaskInfo taskInfo(Issue task, ProjectView project) {
        return new TaskInfo(task.key(), task.title(), task.status(), columnName(project, task.status()), task.workRole(), task.dependsOn(),
                pendingDependencies(task, project), Optional.ofNullable(task.assigneeId()).flatMap(members::find).map(MemberView::name).orElse(null));
    }

    /** Tareas del plan que todavía esperan en la primera columna. */
    List<Issue> waitingTasks(Issue epic, ProjectView project) {
        String first = project.columns().getFirst().key();
        return issues.findChildren(epic.key()).stream().filter(c -> c.workRole() != null && c.status().equals(first)).toList();
    }

    /** Donde se revisa el reparto: la columna de construcción si el flujo la tiene; si no, cualquier etapa de trabajo. */
    boolean planStage(ProjectView project, String column) {
        return buildColumn(project).map(column::equals).orElse(workStage(project, column));
    }

    /**
     * La épica llegó a una etapa de trabajo con su reparto sin aprobar. Si la etapa tiene «el reparto arranca solo», las
     * tareas arrancan ya; si no, lo avisa y nada arranca hasta que una persona lo apruebe.
     */
    void announcePendingPlan(Issue epic, ProjectView project, UUID actorId) {
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
     * Por qué todavía no se puede aprobar el reparto (vacío si ya se puede): la épica debe estar donde se construye o más
     * adelante sin haber terminado; sin columna de construcción, en una etapa de trabajo.
     */
    Optional<String> planNotReady(Issue epic, ProjectView project) {
        Optional<String> build = buildColumn(project);
        boolean ready = build.map(b -> project.indexOf(epic.status()) >= project.indexOf(b) && !isDone(project, epic.status()))
                .orElse(workStage(project, epic.status()));
        if (ready) return Optional.empty();
        return Optional.of("El reparto se aprueba cuando la épica llega a «" + build.map(b -> columnName(project, b)).orElse("un paso de trabajo")
                + "»; ahora está en «" + columnName(project, epic.status()) + "».");
    }

    /**
     * El reparto de una épica para el tablero: sus tareas y si ya se puede aprobar, con la misma regla que
     * {@link #reviewPlan}. Así el botón aparece exactamente cuando la API lo acepta.
     */
    IssueService.PlanStatus planStatus(String epicKey) {
        Issue epic = load(epicKey);
        ProjectView project = project(epic.projectKey());
        List<TaskInfo> tasks = epicTasks(epicKey);
        if (epic.type() != IssueType.EPIC || waitingTasks(epic, project).isEmpty()) return new IssueService.PlanStatus(tasks, false, null);
        Optional<String> notYet = planNotReady(epic, project);
        return new IssueService.PlanStatus(tasks, notYet.isEmpty(), notYet.orElse(null));
    }

    /**
     * Reparto aprobado: las tareas del plan arrancan donde se construye (Desarrollo), cada una para su rol, y la épica
     * espera ahí. Si la épica quedó más adelante (p. ej. la llevaron a QA antes de aprobar), vuelve a esa columna.
     */
    void releaseTasks(Issue epic, ProjectView project, UUID actorId) {
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
    void closeEpicIfDone(String epicKey, ProjectView project, UUID actorId) {
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
    void place(Issue issue, ProjectView project, String target, UUID actorId) {
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

    /**
     * Tarea de una épica en la columna donde se construye: la cubre su rol, no la etapa. Fuera de ella (QA, revisión…)
     * la atiende la etapa, como a cualquier tarjeta. En flujos sin etapa de código, vale la columna de su épica.
     */
    boolean inWorkColumn(Issue issue) {
        if (issue.workRole() == null || issue.parentKey() == null) return false;
        Optional<String> build = projects.find(issue.projectKey()).flatMap(this::buildColumn);
        if (build.isPresent()) return build.get().equals(issue.status());
        return issues.findByKey(issue.parentKey()).map(p -> p.status().equals(issue.status())).orElse(false);
    }

    /**
     * Una épica con tareas sin terminar no pasa de la columna de construcción: avanza sola cuando terminan (a Release o a
     * Hecho). Llevarla antes a QA dejaba sus tareas arrancando en QA, atendidas por el rol equivocado.
     */
    void requireEpicWaitsForTasks(ProjectView project, Issue issue, String target) {
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
    List<Issue> openTasks(Issue epic, ProjectView project) {
        return issues.findChildren(epic.key()).stream().filter(c -> c.workRole() != null && !isDone(project, c.status())).toList();
    }

    /** Dependencias que todavía no están listas para que otras tareas sigan. */
    List<String> pendingDependencies(Issue issue, ProjectView project) {
        return issue.dependsOn().stream()
                .filter(k -> issues.findByKey(k).map(d -> !readyForDependents(project, d.status())).orElse(false))
                .toList();
    }

    /**
     * Épica con reparto de tareas que nadie está trabajando: la trabajan sus tareas (o espera que una persona apruebe el
     * reparto). Si un agente la tiene (p. ej. el arquitecto rehaciendo el plan), sigue en su cola.
     */
    boolean handledByTasks(Issue issue) {
        return issue.type() == IssueType.EPIC && issue.assigneeId() == null
                && issues.findChildren(issue.key()).stream().anyMatch(c -> c.workRole() != null);
    }
}
