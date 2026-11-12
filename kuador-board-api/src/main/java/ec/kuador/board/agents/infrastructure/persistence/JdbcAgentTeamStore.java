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
        return jdbc.sql("select member_id, mode, model from agent_profile where workspace_id = ?").param(workspaceId)
                .query((rs, n) -> new Profile(rs.getObject(1, UUID.class), AgentMode.valueOf(rs.getString(2)), rs.getString(3))).list().stream()
                .collect(Collectors.toMap(Profile::memberId, Function.identity()));
    }

    @Override
    public Optional<Profile> profile(UUID memberId) {
        return jdbc.sql("select member_id, mode, model from agent_profile where member_id = ?").param(memberId)
                .query((rs, n) -> new Profile(rs.getObject(1, UUID.class), AgentMode.valueOf(rs.getString(2)), rs.getString(3))).optional();
    }

    @Override
    public void saveProfile(UUID workspaceId, Profile p, Instant now) {
        jdbc.sql("""
                insert into agent_profile (member_id, workspace_id, mode, model, updated_at) values (?, ?, ?, ?, ?)
                on conflict (member_id) do update set mode = excluded.mode, model = excluded.model, updated_at = excluded.updated_at""")
                .params(p.memberId(), workspaceId, p.mode().name(), p.model(), Timestamp.from(now)).update();
    }

    @Override
    public List<UUID> team(UUID workspaceId, String projectKey) {
        return jdbc.sql("select member_id from project_agent where workspace_id = ? and project_key = ? order by added_at")
                .params(workspaceId, projectKey).query(UUID.class).list();
    }

    @Override
    public List<String> boardsOf(UUID workspaceId, UUID memberId) {
        return jdbc.sql("select project_key from project_agent where workspace_id = ? and member_id = ? order by project_key")
                .params(workspaceId, memberId).query(String.class).list();
    }

    @Override
    public void addToTeam(UUID workspaceId, String projectKey, UUID memberId) {
        jdbc.sql("insert into project_agent (workspace_id, project_key, member_id) values (?, ?, ?) on conflict do nothing")
                .params(workspaceId, projectKey, memberId).update();
    }

    @Override
    public void removeFromTeam(UUID workspaceId, String projectKey, UUID memberId) {
        jdbc.sql("delete from project_agent where workspace_id = ? and project_key = ? and member_id = ?").params(workspaceId, projectKey, memberId).update();
    }

    @Override
    public Optional<AiKey> aiKey(UUID workspaceId) {
        return jdbc.sql("select api_key_enc, key_hint, default_model from workspace_ai where workspace_id = ?").param(workspaceId)
                .query((rs, n) -> new AiKey(rs.getString(1), rs.getString(2), rs.getString(3))).optional();
    }

    @Override
    public void saveAiKey(UUID workspaceId, AiKey key, Instant now) {
        jdbc.sql("""
                insert into workspace_ai (workspace_id, api_key_enc, key_hint, default_model, updated_at) values (?, ?, ?, ?, ?)
                on conflict (workspace_id) do update set api_key_enc = excluded.api_key_enc, key_hint = excluded.key_hint,
                    default_model = excluded.default_model, updated_at = excluded.updated_at""")
                .params(workspaceId, key.encryptedKey(), key.hint(), key.defaultModel(), Timestamp.from(now)).update();
    }

    @Override
    public void deleteAiKey(UUID workspaceId) {
        jdbc.sql("delete from workspace_ai where workspace_id = ?").param(workspaceId).update();
    }
}
