package ec.kuador.board.project.infrastructure.persistence;

import ec.kuador.board.project.domain.BoardPeople;
import ec.kuador.board.shared.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL directo; cada consulta filtra por el espacio en contexto. */
@Repository
class JdbcBoardPeople implements BoardPeople {

    private final JdbcClient jdbc;

    JdbcBoardPeople(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> of(String projectKey) {
        return jdbc.sql("select member_id from project_member where workspace_id = ? and project_key = ? order by added_at")
                .params(TenantContext.require(), projectKey).query(UUID.class).list();
    }

    @Override
    public List<String> boardsOf(UUID memberId) {
        return jdbc.sql("select project_key from project_member where workspace_id = ? and member_id = ? order by project_key")
                .params(TenantContext.require(), memberId).query(String.class).list();
    }

    @Override
    public List<String> agentBoardsOf(UUID agentId) {
        return jdbc.sql("select project_key from project_agent where workspace_id = ? and member_id = ? order by project_key")
                .params(TenantContext.require(), agentId).query(String.class).list();
    }

    @Override
    public boolean contains(String projectKey, UUID memberId) {
        return jdbc.sql("select count(*) from project_member where workspace_id = ? and project_key = ? and member_id = ?")
                .params(TenantContext.require(), projectKey, memberId).query(Long.class).single() > 0;
    }

    @Override
    public void add(String projectKey, UUID memberId) {
        jdbc.sql("insert into project_member (workspace_id, project_key, member_id) values (?, ?, ?) on conflict do nothing")
                .params(TenantContext.require(), projectKey, memberId).update();
    }

    @Override
    public void remove(String projectKey, UUID memberId) {
        jdbc.sql("delete from project_member where workspace_id = ? and project_key = ? and member_id = ?")
                .params(TenantContext.require(), projectKey, memberId).update();
    }
}
