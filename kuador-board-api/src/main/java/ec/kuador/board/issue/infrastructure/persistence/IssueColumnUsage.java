package ec.kuador.board.issue.infrastructure.persistence;

import ec.kuador.board.issue.domain.IssueRepository;
import ec.kuador.board.project.ColumnUsage;
import org.springframework.stereotype.Component;

/** Implementa el puerto de project: project pregunta sin depender del módulo issue. */
@Component
class IssueColumnUsage implements ColumnUsage {

    private final IssueRepository issues;

    IssueColumnUsage(IssueRepository issues) {
        this.issues = issues;
    }

    @Override
    public long countIssues(String projectKey, String columnKey) {
        return issues.countInColumn(projectKey, columnKey);
    }
}
