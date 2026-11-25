package ec.kuador.board.issue.application;

import static ec.kuador.board.issue.application.Flow.*;

import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.ForbiddenException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Qué persona pone a trabajar a los agentes: quien les pasa una tarjeta pasa a supervisarla. */
final class Supervision extends IssueSupport {

    Supervision(Ports ports) {
        super(ports);
    }

    /**
     * Cuando una persona le pasa trabajo a los agentes (lo asigna a un agente o lo lleva a una etapa que
     * cubren agentes), pasa a supervisarlo: solo sus agentes, en su cliente de IA, lo atienden. Lo que
     * hacen los agentes no cambia quién supervisa: se hereda por toda la cadena.
     */
    void superviseIfPersonHandsToAgents(Issue issue, ProjectView project, UUID actorId) {
        if (actorId == null) return;
        Optional<MemberView> actor = members.find(actorId);
        if (actor.isEmpty() || actor.get().agent()) return;
        boolean toAgent = issue.assigneeId() != null && members.find(issue.assigneeId()).map(MemberView::agent).orElse(false);
        if (toAgent || (issue.assigneeId() == null && !agentRolesOf(project.stage(issue.status())).isEmpty())) {
            issue.superviseBy(actorId);
        }
    }

    /** Roles de la etapa que cubre algún agente del espacio. */
    List<String> agentRolesOf(Optional<StageDef> stage) {
        if (stage.isEmpty()) return List.of();
        List<String> agentRoles = members.agents().stream().map(MemberView::role).toList();
        return stage.get().roles().stream().filter(agentRoles::contains).toList();
    }

    /** Una persona se encarga de que sus agentes atiendan el issue (p. ej. si lo dejó otra persona). */
    Issue supervise(String key, UUID actorId) {
        Issue issue = load(key);
        MemberView actor = requireMember(actorId);
        if (actor.agent()) throw new ForbiddenException("Solo una persona supervisa el trabajo de los agentes.");
        issue.superviseBy(actorId);
        return issues.save(issue);
    }
}
