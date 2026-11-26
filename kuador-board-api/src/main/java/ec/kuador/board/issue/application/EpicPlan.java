package ec.kuador.board.issue.application;

import static ec.kuador.board.issue.application.Flow.*;

import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.issue.IssueApi.IssueRef;
import ec.kuador.board.issue.IssueApi.PlannedTask;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.issue.domain.IssueDetails;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.ForbiddenException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** El plan de una épica: crear sus tareas y que una persona apruebe el reparto o pida cambios. */
final class EpicPlan extends IssueSupport {

    private final IssueEditor editor;
    private final IssueMover mover;
    private final EpicTasks epicTasks;

    EpicPlan(Ports ports, IssueEditor editor, IssueMover mover, EpicTasks epicTasks) {
        super(ports);
        this.editor = editor;
        this.mover = mover;
        this.epicTasks = epicTasks;
    }

    /** Tope de tareas por épica: más es ruido en el tablero. */
    static final int MAX_TASKS = 40;

    List<IssueRef> createEpicTasks(String epicKey, List<PlannedTask> tasks, UUID actorId) {
        Issue epic = load(epicKey);
        ProjectView project = project(epic.projectKey());
        if (!project.splits()) throw new DomainException("Este tablero no reparte el trabajo en tareas (se enciende en Configuración → General).");
        boolean promote = epic.type() != IssueType.EPIC;
        if (promote && !epicTasks.splittable(epic, project)) {
            throw new DomainException(epicKey + " no es una épica: las tareas del plan se crean en una épica (o en una historia sin épica, que pasa a serlo).");
        }
        // Se reparte en la etapa que planea (en la fábrica, Diseño, con el arquitecto): nunca antes, p. ej. en Refinamiento
        // o con la épica esperando en Aprobación. Más adelante sí, si la devolvieron o la movieron antes de repartir.
        Optional<String> at = project.splitColumn();
        if (at.isPresent() && project.indexOf(epic.status()) < project.indexOf(at.get())) {
            throw new DomainException("El reparto en tareas se hace en «" + columnName(project, at.get()) + "», no en «"
                    + columnName(project, epic.status()) + "»: ahí lo hace el rol que planea. Entrega " + epicKey + " sin crear tareas.");
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
            editor.edit(epicKey, new IssueDetails(epic.title(), epic.description(), IssueType.EPIC, epic.priority(), epic.storyPoints(), epic.assigneeId(), null,
                    epic.labels()), actorId);
            saveComment(epic, actorId, "Era " + was + ": pasa a **épica** porque el plan la reparte en " + tasks.size() + " tarea(s).", now());
            epic = load(epicKey);
        }
        previous.forEach(c -> editor.delete(c.key(), actorId));
        List<Issue> created = new ArrayList<>();
        for (PlannedTask t : tasks) {
            Issue task = editor.create(epic.projectKey(), new IssueDetails(t.title().strip(), t.description(), IssueType.TASK, epic.priority(), null, null, epicKey, List.of()),
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
        if (epicTasks.planStage(project, epic.status())) {
            epicTasks.releaseEpicFromAgent(epic, actorId);
            epicTasks.announcePendingPlan(epic, project, actorId);
        }
        return created.stream().map(c -> new IssueRef(c.key(), c.projectKey(), Integer.parseInt(c.key().substring(c.key().lastIndexOf('-') + 1)), c.title(), c.subjectKey())).toList();
    }

    /**
     * Una persona revisa el reparto de tareas de la épica. Aprobar: las tareas arrancan en la etapa de la épica, cada una
     * para su rol. Pedir cambios: la épica vuelve a la etapa anterior (el diseño) con el motivo; el plan se rehace.
     */
    Issue reviewPlan(String epicKey, boolean approve, String comment, UUID actorId) {
        Issue epic = load(epicKey);
        ProjectView project = project(epic.projectKey());
        MemberView person = requireMember(actorId);
        if (person.agent()) throw new ForbiddenException("El reparto de tareas lo aprueba una persona, no un agente.");
        if (epic.type() != IssueType.EPIC) throw new DomainException(epicKey + " no es una épica.");
        List<Issue> waiting = epicTasks.waitingTasks(epic, project);
        if (waiting.isEmpty()) throw new DomainException(epicKey + " no tiene un reparto de tareas por aprobar.");
        Optional<String> notYet = epicTasks.planNotReady(epic, project);
        if (notYet.isPresent()) throw new DomainException(notYet.get());
        if (approve) {
            saveComment(epic, actorId, "**Reparto aprobado** por " + person.name() + "." + (comment == null || comment.isBlank() ? "" : "\n\n" + comment.strip()), now());
            epicTasks.releaseTasks(epic, project, actorId);
            return load(epicKey);
        }
        if (comment == null || comment.isBlank()) throw new DomainException("Para pedir cambios al reparto, explica qué hay que cambiar.");
        String back = previousColumn(project, epic.status())
                .orElseThrow(() -> new DomainException("No hay a qué paso devolver la épica: revisa las transiciones del flujo."));
        saveComment(epic, actorId, "**Cambios al reparto** pedidos por " + person.name() + ": " + comment.strip(), now());
        Issue moved = mover.move(epicKey, back, Integer.MAX_VALUE, actorId);
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
}
