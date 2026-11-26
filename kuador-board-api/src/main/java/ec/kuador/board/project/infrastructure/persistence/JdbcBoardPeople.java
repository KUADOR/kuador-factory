package ec.kuador.board.project.infrastructure.persistence;

import ec.kuador.board.project.domain.BoardPeople;
import ec.kuador.board.shared.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las personas de un tablero son las de su equipo (ver {@link JdbcTeams}). SQL directo; cada consulta filtra por el
 * espacio en contexto.
 */
@Repository
class JdbcBoardPeople implements BoardPeople {

    private static final String OF_BOARD = """
            from team_member tm
            join project p on p.team_id = tm.team_id and p.workspace_id = tm.workspace_id
            join member m on m.id = tm.member_id
            where tm.workspace_id = ? and p.project_key = ? and m.kind = 'HUMAN'""";

    private static final String BOARDS_OF = """
            select p.project_key from project p
            join team_member tm on tm.team_id = p.team_id and tm.workspace_id = p.workspace_id
            join member m on m.id = tm.member_id
            where p.workspace_id = ? and tm.member_id = ? and m.kind = ? order by p.project_key""";

    private final JdbcClient jdbc;

    JdbcBoardPeople(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> of(String projectKey) {
        return jdbc.sql("select tm.member_id " + OF_BOARD + " order by tm.added_at, tm.member_id")
                .params(TenantContext.require(), projectKey).query(UUID.class).list();
    }

    @Override
    public List<String> boardsOf(UUID memberId) {
        return jdbc.sql(BOARDS_OF).params(TenantContext.require(), memberId, "HUMAN").query(String.class).list();
    }

    @Override
    public List<String> agentBoardsOf(UUID agentId) {
        return jdbc.sql(BOARDS_OF).params(TenantContext.require(), agentId, "AGENT").query(String.class).list();
    }

    @Override
    public boolean contains(String projectKey, UUID memberId) {
        return jdbc.sql("select count(*) " + OF_BOARD + " and tm.member_id = ?")
                .params(TenantContext.require(), projectKey, memberId).query(Long.class).single() > 0;
    }

    /** Suma la persona al equipo del tablero (y con eso, a los demás tableros de ese equipo). */
    @Override
    public void add(String projectKey, UUID memberId) {
        jdbc.sql("""
                insert into team_member (team_id, member_id, workspace_id)
                select p.team_id, ?, p.workspace_id from project p where p.workspace_id = ? and p.project_key = ? and p.team_id is not null
                on conflict do nothing""").params(memberId, TenantContext.require(), projectKey).update();
    }

    @Override
    public void remove(String projectKey, UUID memberId) {
        jdbc.sql("""
                delete from team_member tm using project p
                 where p.team_id = tm.team_id and p.workspace_id = tm.workspace_id
                   and p.workspace_id = ? and p.project_key = ? and tm.member_id = ?""")
                .params(TenantContext.require(), projectKey, memberId).update();
    }
}
