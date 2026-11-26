package ec.kuador.board.agents.infrastructure.persistence;

import ec.kuador.board.agents.domain.AgentMode;
import ec.kuador.board.agents.domain.AgentTeamStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL directo; cada consulta filtra por el espacio que recibe. */
@Repository
class JdbcAgentTeamStore implements AgentTeamStore {

    private final JdbcClient jdbc;

    JdbcAgentTeamStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, Profile> profiles(UUID workspaceId) {
        return jdbc.sql("select member_id, mode, model, effort from agent_profile where workspace_id = ?").param(workspaceId)
                .query((rs, n) -> new Profile(rs.getObject(1, UUID.class), AgentMode.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4))).list().stream()
                .collect(Collectors.toMap(Profile::memberId, Function.identity()));
    }

    @Override
    public Optional<Profile> profile(UUID memberId) {
        return jdbc.sql("select member_id, mode, model, effort from agent_profile where member_id = ?").param(memberId)
                .query((rs, n) -> new Profile(rs.getObject(1, UUID.class), AgentMode.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4))).optional();
    }

    @Override
    public void saveProfile(UUID workspaceId, Profile p, Instant now) {
        jdbc.sql("""
                insert into agent_profile (member_id, workspace_id, mode, model, effort, updated_at) values (?, ?, ?, ?, ?, ?)
                on conflict (member_id) do update set mode = excluded.mode, model = excluded.model, effort = excluded.effort,
                    updated_at = excluded.updated_at""")
                .params(p.memberId(), workspaceId, p.mode().name(), p.model(), p.effort(), Timestamp.from(now)).update();
    }

    /** El equipo de agentes de un tablero: los agentes del equipo que lo atiende (team_member). */
    @Override
    public List<UUID> team(UUID workspaceId, String projectKey) {
        return jdbc.sql("""
                select tm.member_id from team_member tm
                join project p on p.team_id = tm.team_id and p.workspace_id = tm.workspace_id
                join member m on m.id = tm.member_id
                where tm.workspace_id = ? and p.project_key = ? and m.kind = 'AGENT' order by tm.added_at, tm.member_id""")
                .params(workspaceId, projectKey).query(UUID.class).list();
    }

    @Override
    public List<String> boardsOf(UUID workspaceId, UUID memberId) {
        return jdbc.sql("""
                select p.project_key from project p join team_member tm on tm.team_id = p.team_id and tm.workspace_id = p.workspace_id
                where p.workspace_id = ? and tm.member_id = ? order by p.project_key""")
                .params(workspaceId, memberId).query(String.class).list();
    }

    /** Suma el agente al equipo del tablero (y con eso, a los demás tableros de ese equipo). */
    @Override
    public void addToTeam(UUID workspaceId, String projectKey, UUID memberId) {
        jdbc.sql("""
                insert into team_member (team_id, member_id, workspace_id)
                select p.team_id, ?, p.workspace_id from project p where p.workspace_id = ? and p.project_key = ? and p.team_id is not null
                on conflict do nothing""").params(memberId, workspaceId, projectKey).update();
    }

    @Override
    public void removeFromTeam(UUID workspaceId, String projectKey, UUID memberId) {
        jdbc.sql("""
                delete from team_member tm using project p
                 where p.team_id = tm.team_id and p.workspace_id = tm.workspace_id
                   and p.workspace_id = ? and p.project_key = ? and tm.member_id = ?""").params(workspaceId, projectKey, memberId).update();
    }

    @Override
    public List<UUID> teamAgents(UUID workspaceId, UUID teamId) {
        return jdbc.sql("""
                select tm.member_id from team_member tm join member m on m.id = tm.member_id
                where tm.workspace_id = ? and tm.team_id = ? and m.kind = 'AGENT' order by tm.added_at, tm.member_id""")
                .params(workspaceId, teamId).query(UUID.class).list();
    }

    @Override
    public void addToTeamById(UUID workspaceId, UUID teamId, UUID memberId) {
        jdbc.sql("""
                insert into team_member (team_id, member_id, workspace_id)
                select t.id, ?, t.workspace_id from team t where t.workspace_id = ? and t.id = ?
                on conflict do nothing""").params(memberId, workspaceId, teamId).update();
    }

    @Override
    public Optional<AiKey> aiKey(UUID workspaceId) {
        return jdbc.sql("select provider, api_key_enc, key_hint, default_model from workspace_ai where workspace_id = ?").param(workspaceId)
                .query((rs, n) -> new AiKey(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).optional();
    }

    @Override
    public void saveAiKey(UUID workspaceId, AiKey key, Instant now) {
        jdbc.sql("""
                insert into workspace_ai (workspace_id, provider, api_key_enc, key_hint, default_model, updated_at) values (?, ?, ?, ?, ?, ?)
                on conflict (workspace_id) do update set provider = excluded.provider, api_key_enc = excluded.api_key_enc,
                    key_hint = excluded.key_hint, default_model = excluded.default_model, updated_at = excluded.updated_at""")
                .params(workspaceId, key.provider(), key.encryptedKey(), key.hint(), key.defaultModel(), Timestamp.from(now)).update();
    }

    @Override
    public void deleteAiKey(UUID workspaceId) {
        jdbc.sql("delete from workspace_ai where workspace_id = ?").param(workspaceId).update();
    }
}
