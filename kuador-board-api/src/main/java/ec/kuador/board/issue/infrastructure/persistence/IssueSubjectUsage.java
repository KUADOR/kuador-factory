package ec.kuador.board.issue.infrastructure.persistence;

import ec.kuador.board.project.SubjectUsage;
import ec.kuador.board.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Implementa el puerto de project: cuántas tarjetas tiene un proyecto en un tablero. */
@Component
class IssueSubjectUsage implements SubjectUsage {

    private final JdbcClient jdbc;

    IssueSubjectUsage(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long countIssues(String projectKey, String subjectKey) {
        return jdbc.sql("select count(*) from issue where workspace_id = ? and project_key = ? and subject_key = ?")
                .params(TenantContext.require(), projectKey, subjectKey).query(Long.class).single();
    }
}
