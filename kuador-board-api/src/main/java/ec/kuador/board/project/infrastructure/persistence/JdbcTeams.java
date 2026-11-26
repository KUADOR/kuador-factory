package ec.kuador.board.project.infrastructure.persistence;

import ec.kuador.board.project.domain.Team;
import ec.kuador.board.project.domain.TeamRepository;
import ec.kuador.board.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL directo; cada consulta filtra por el espacio en contexto. */
@Repository
class JdbcTeams implements TeamRepository {

    private static final RowMapper<Team> ROW = (rs, n) -> new Team(rs.getObject("id", UUID.class), rs.getString("name"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    JdbcTeams(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Team> findAll() {
        return jdbc.sql("select id, name, created_at from team where workspace_id = ? order by lower(name)")
                .params(TenantContext.require()).query(ROW).list();
    }

    @Override
    public Optional<Team> find(UUID id) {
        return jdbc.sql("select id, name, created_at from team where workspace_id = ? and id = ?")
                .params(TenantContext.require(), id).query(ROW).optional();
    }

    @Override
    public boolean nameTaken(String name, UUID except) {
        return jdbc.sql("select count(*) from team where workspace_id = ? and lower(name) = lower(?) and id <> ?")
                .params(TenantContext.require(), name.strip(), except == null ? new UUID(0, 0) : except).query(Long.class).single() > 0;
    }

    @Override
    public void insert(Team t) {
        jdbc.sql("insert into team (id, workspace_id, name, created_at) values (?, ?, ?, ?)")
                .params(t.id(), TenantContext.require(), t.name(), Timestamp.from(t.createdAt())).update();
    }

    @Override
    public void rename(UUID id, String name) {
        jdbc.sql("update team set name = ? where workspace_id = ? and id = ?").params(name, TenantContext.require(), id).update();
    }

    @Override
    public void delete(UUID id) {
        jdbc.sql("delete from team where workspace_id = ? and id = ?").params(TenantContext.require(), id).update();
    }

    @Override
    public List<UUID> members(UUID teamId) {
        return jdbc.sql("select member_id from team_member where workspace_id = ? and team_id = ? order by added_at, member_id")
                .params(TenantContext.require(), teamId).query(UUID.class).list();
    }

    @Override
    public void addMember(UUID teamId, UUID memberId) {
        jdbc.sql("insert into team_member (team_id, member_id, workspace_id) values (?, ?, ?) on conflict do nothing")
                .params(teamId, memberId, TenantContext.require()).update();
    }

    @Override
    public void removeMember(UUID teamId, UUID memberId) {
        jdbc.sql("delete from team_member where workspace_id = ? and team_id = ? and member_id = ?")
                .params(TenantContext.require(), teamId, memberId).update();
    }

    @Override
    public List<String> boards(UUID teamId) {
        return jdbc.sql("select project_key from project where workspace_id = ? and team_id = ? order by project_key")
                .params(TenantContext.require(), teamId).query(String.class).list();
    }

    @Override
    public List<UUID> teamsOf(UUID memberId) {
        return jdbc.sql("select team_id from team_member where workspace_id = ? and member_id = ?")
                .params(TenantContext.require(), memberId).query(UUID.class).list();
    }

    @Override
    public Optional<UUID> teamOf(String projectKey) {
        return jdbc.sql("select team_id from project where workspace_id = ? and project_key = ? and team_id is not null")
                .params(TenantContext.require(), projectKey).query(UUID.class).optional();
    }

    @Override
    public void assign(String projectKey, UUID teamId) {
        jdbc.sql("update project set team_id = ? where workspace_id = ? and project_key = ?").params(teamId, TenantContext.require(), projectKey).update();
    }
}
