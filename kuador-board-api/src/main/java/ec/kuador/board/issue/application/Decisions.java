package ec.kuador.board.issue.application;

import static ec.kuador.board.issue.application.Flow.*;

import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.domain.Comment;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.issue.domain.IssueFilter;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Lo que espera la decisión de una persona en los tableros que ve. */
final class Decisions extends IssueSupport {

    private final Gates gates;
    private final EpicTasks tasks;

    Decisions(Ports ports, Gates gates, EpicTasks tasks) {
        super(ports);
        this.gates = gates;
        this.tasks = tasks;
    }

    /** Lo que espera a esta persona en los tableros que ve, lo más antiguo primero. */
    List<IssueService.Decision> decisionsFor(UUID memberId) {
        MemberView me = requireMember(memberId);
        if (me.agent()) return List.of();
        List<IssueService.Decision> out = new ArrayList<>();
        for (String key : projects.visibleKeys()) {
            ProjectView project = project(key);
            String projectName = project.name();
            // Puertas que aprueba: sin un agente revisándolas y sin otra persona que ya la tenga.
            for (StageDef gate : project.stages()) {
                if (!gate.gate() || !gate.approvableBy(me.role())) continue;
                for (Issue i : issues.findColumn(key, gate.column())) {
                    UUID assignee = i.assigneeId();
                    if (assignee != null && !assignee.equals(memberId)) continue;
                    if (assignee == null && gates.agentReviewed(i, project)) continue;
                    out.add(decision("GATE", i, project, projectName, lastComment(i).orElse(null)));
                }
            }
            // Repartos de épicas por aprobar: los ve quien la supervisa (o quien administra, si nadie la supervisa).
            for (Issue epic : issues.findByProject(key, IssueFilter.none())) {
                if (epic.type() != IssueType.EPIC || !workStage(project, epic.status()) || tasks.waitingTasks(epic, project).isEmpty()) continue;
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
        out.sort(java.util.Comparator.comparing(IssueService.Decision::since, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return out;
    }

    IssueService.Decision decision(String kind, Issue i, ProjectView project, String projectName, Comment last) {
        String from = last == null ? null : members.find(last.authorId()).map(MemberView::name).orElse(null);
        String detail = last == null ? null : excerpt(last.body());
        return new IssueService.Decision(kind, i.key(), project.key(), projectName, i.title(), i.status(), columnName(project, i.status()), detail, from,
                last == null ? i.updatedAt() : last.createdAt());
    }

    static String excerpt(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 280 ? flat : flat.substring(0, 277) + "…";
    }
}
