package ec.kuador.board.issue.application;

import static ec.kuador.board.issue.application.Flow.*;

import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.IssueApi.GateReview;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.ForbiddenException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Las puertas de aprobación: quién puede cerrarlas, cuáles revisa un agente y qué no se puede saltar. */
final class Gates extends IssueSupport {

    Gates(Ports ports) {
        super(ports);
    }

    boolean agentReviewed(Issue i, ProjectView project) {
        Optional<StageDef> stage = project.stage(i.status());
        if (stage.isEmpty() || !stage.get().gate() || stage.get().reviewFor(i.reviewBy()) == StageDef.ReviewMode.PERSON) return false;
        String role = stage.get().reviewerRole();
        return members.agents().stream().anyMatch(a -> a.role().equals(role));
    }

    boolean canApproveGate(String issueKey, UUID actorId) {
        Optional<Issue> found = issues.findByKey(issueKey);
        Optional<MemberView> actor = members.find(actorId);
        if (found.isEmpty() || actor.isEmpty() || actor.get().agent()) return false;
        return project(found.get().projectKey()).stage(found.get().status()).filter(StageDef::gate)
                .map(g -> g.approvableBy(actor.get().role())).orElse(false);
    }

    Optional<GateReview> gateReview(String issueKey, UUID agentId) {
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

    /** Quien decide una puerta escalada: quien supervisa la tarjeta si puede aprobarla; si no, una persona aprobadora. */
    MemberView approverFor(Issue issue, StageDef gate) {
        return Optional.ofNullable(issue.supervisorId()).flatMap(members::find).filter(m -> !m.agent() && gate.approvableBy(m.role()))
                .or(() -> members.humans().stream().filter(m -> gate.approvableBy(m.role()) && "OWNER".equals(m.role())).findFirst())
                .or(() -> members.humans().stream().filter(m -> gate.approvableBy(m.role())).findFirst())
                .or(() -> responsibleHuman(issue))
                .orElseThrow(() -> new DomainException("No hay una persona que pueda aprobar este paso."));
    }

    String approverNames(ProjectView project, StageDef gate) {
        return gate.approvers().isEmpty() ? "cualquier persona del equipo" : roleNames(gate.approvers());
    }

    /**
     * Las puertas que quedan entre {@code from} (exclusivo; {@code null} = antes de la primera columna) y {@code target}
     * (exclusivo), hacia adelante: pasar por encima de una exige poder aprobarla.
     */
    void requireGatesCrossed(ProjectView project, String from, String target, UUID actorId, Issue issue) {
        List<String> order = project.columns().stream().map(ColumnDef::key).toList();
        int fromIndex = from == null ? -1 : order.indexOf(from);
        int toIndex = order.indexOf(target);
        if (toIndex <= fromIndex + 1) return;
        for (int i = fromIndex + 1; i < toIndex; i++) {
            Optional<StageDef> gate = project.stage(order.get(i)).filter(StageDef::gate);
            if (gate.isPresent()) requireApprover(project, gate.get(), actorId, issue);
        }
    }

    /**
     * Cierra una puerta una persona (con uno de sus roles aprobadores, si los pide) o, si la puerta está en revisión por
     * agente para esta tarjeta, su agente revisor.
     */
    MemberView requireApprover(ProjectView project, StageDef gate, UUID actorId, Issue issue) {
        MemberView actor = requireMember(actorId);
        String gateName = columnName(project, gate.column());
        if (actor.agent()) {
            if (gate.reviewFor(issue.reviewBy()) != StageDef.ReviewMode.PERSON && actor.role().equals(gate.reviewerRole())) return actor;
            throw new ForbiddenException("«" + gateName + "» es un paso de aprobación: lo cierra una persona, no un agente.");
        }
        if (!gate.approvableBy(actor.role())) {
            throw new ForbiddenException("«" + gateName + "» la aprueba: " + roleNames(gate.approvers()) + ".");
        }
        return actor;
    }
}
