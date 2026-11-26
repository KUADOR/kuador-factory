package ec.kuador.board.issue.application;

import static ec.kuador.board.issue.application.Flow.*;

import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.IssueApi.QueueItem;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.issue.domain.IssueFilter;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Las colas de los agentes: qué tarjetas esperan a un agente y con qué rol. */
final class AgentQueues extends IssueSupport {

    private final EpicTasks tasks;
    private final Gates gates;
    private final StageEvidence evidence;
    private final Supervision supervision;

    AgentQueues(Ports ports, EpicTasks tasks, Gates gates, StageEvidence evidence, Supervision supervision) {
        super(ports);
        this.tasks = tasks;
        this.gates = gates;
        this.evidence = evidence;
        this.supervision = supervision;
    }

    /**
     * Cola de los agentes de una persona: los issues que supervisa y que esperan a un agente, ya sea
     * asignados a uno o sin tomar en una etapa que cubren agentes. No incluye lo terminado ni las
     * puertas que esperan a una persona.
     */
    List<QueueItem> agentQueue(UUID supervisorId) {
        java.util.Map<String, Optional<ProjectView>> byKey = new java.util.HashMap<>();
        List<QueueItem> queue = new ArrayList<>();
        for (Issue i : issues.findBySupervisor(supervisorId)) {
            ProjectView project = byKey.computeIfAbsent(i.projectKey(), projects::find).orElse(null);
            if (project != null) queueItem(i, project).ifPresent(queue::add);
        }
        return queue;
    }

    List<QueueItem> openForAgents(String projectKey) {
        ProjectView project = projects.find(projectKey).orElse(null);
        if (project == null) return List.of();
        return issues.findByProject(projectKey, IssueFilter.none()).stream()
                .filter(i -> i.assigneeId() == null)
                .flatMap(i -> queueItem(i, project).filter(q -> !q.gate() || gates.agentReviewed(i, project)).stream())
                .toList();
    }

    List<QueueItem> waitingForAgents(String projectKey) {
        ProjectView project = projects.find(projectKey).orElse(null);
        if (project == null) return List.of();
        return issues.findByProject(projectKey, IssueFilter.none()).stream()
                .flatMap(i -> queueItem(i, project).filter(q -> !q.gate() || gates.agentReviewed(i, project)).stream())
                .toList();
    }

    /** El issue espera a un agente (asignado a uno o por tomar en una etapa que cubren agentes); si no, vacío. */
    Optional<QueueItem> queueItem(Issue i, ProjectView project) {
        Optional<ColumnDef> column = project.column(i.status());
        if (column.isEmpty() || column.get().category() == ec.kuador.board.catalog.ColumnCategory.DONE) return Optional.empty();
        Optional<StageDef> stage = project.stage(i.status());
        Optional<MemberView> assignee = Optional.ofNullable(i.assigneeId()).flatMap(members::find);
        if (tasks.handledByTasks(i)) return Optional.empty(); // la trabajan sus tareas, o espera la aprobación del reparto
        if (!tasks.pendingDependencies(i, project).isEmpty()) return Optional.empty(); // espera a otras tareas
        List<String> roles;
        if (assignee.isPresent()) {
            if (!assignee.get().agent()) return Optional.empty();
            roles = List.of(assignee.get().role());
        } else if (tasks.inWorkColumn(i)) {
            if (members.agents().stream().noneMatch(a -> a.role().equals(i.workRole()))) return Optional.empty(); // la hace una persona
            roles = List.of(i.workRole());
        } else if (gates.agentReviewed(i, project)) {
            // Puerta que revisa un agente: la toma su agente revisor.
            roles = List.of(stage.get().reviewerRole());
        } else {
            // Una tarea del plan que aún no arranca (sigue en la primera columna) no es de nadie todavía.
            if (i.workRole() != null && i.status().equals(project.columns().getFirst().key())) return Optional.empty();
            // Una épica en una etapa con roles previos (UI/UX en Diseño): primero el que falta, después los de la etapa.
            Optional<String> first = stage.flatMap(st -> evidence.pendingFirstRole(i, project, st));
            roles = first.isPresent() ? List.of(first.get()) : supervision.agentRolesOf(stage);
            if (roles.isEmpty()) return Optional.empty();
        }
        return Optional.of(new QueueItem(i.key(), i.projectKey(), i.title(), i.type().name(), i.priority().name(), i.status(), column.get().name(),
                stage.map(StageDef::gate).orElse(false), roles, assignee.map(MemberView::id).orElse(null),
                assignee.map(MemberView::name).orElse(null), i.reworkCount(), i.subjectKey()));
    }
}
