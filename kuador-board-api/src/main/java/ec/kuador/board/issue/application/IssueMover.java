package ec.kuador.board.issue.application;

import static ec.kuador.board.issue.application.Flow.*;

import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.IssueMoved;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Mover una tarjeta de columna: transiciones del flujo, puertas, freno de bucles y lo que el movimiento desencadena. */
final class IssueMover extends IssueSupport {

    private final Gates gates;
    private final Supervision supervision;
    private final EpicTasks tasks;

    IssueMover(Ports ports, Gates gates, Supervision supervision, EpicTasks tasks) {
        super(ports);
        this.gates = gates;
        this.supervision = supervision;
        this.tasks = tasks;
    }

    /** Mueve el issue a {@code target} en la posición {@code index} (0 = arriba) y reordena la columna. */
    Issue move(String key, String target, int index, UUID actorId) {
        return move(key, target, index, actorId, false);
    }

    /**
     * {@code byRule}: lo mueve una regla fija del tablero (p. ej. devolver una tarjeta cuyo PR quedó en conflicto), no la
     * decisión de quien figura como autor: no exige ser aprobador de la puerta de la que sale.
     */
    Issue move(String key, String target, int index, UUID actorId, boolean byRule) {
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
        if (fromStage.isPresent() && fromStage.get().gate() && !byRule) {
            gates.requireApprover(project, fromStage.get(), actorId, issue);
        }
        // Tampoco se salta una puerta llevando la tarjeta de antes a después de ella.
        gates.requireGatesCrossed(project, from, target, actorId, issue);
        tasks.requireEpicWaitsForTasks(project, issue, target);
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
        if (!from.equals(target)) supervision.superviseIfPersonHandsToAgents(issue, project, actorId);
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
            if (issue.type() == IssueType.EPIC) tasks.announcePendingPlan(issue, project, actorId);
            if (issue.workRole() != null && issue.parentKey() != null && releaseColumn(project).filter(target::equals).isPresent()) {
                // Una tarea termina con su PR aprobado: el release (develop → main) se hace una vez, con la épica.
                firstDone(project).ifPresent(done -> {
                    tasks.place(issue, project, done.key(), actorId);
                    saveComment(issue, actorId, "Tarea terminada: su PR quedó aprobado. El release se hace con la épica " + issue.parentKey() + ".", now());
                });
            }
            if (issue.parentKey() != null && isDone(project, issue.status())) tasks.closeEpicIfDone(issue.parentKey(), project, actorId);
        }
        return issue;
    }
}
