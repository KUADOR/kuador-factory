package ec.kuador.board.connections.infrastructure.persistence;

import ec.kuador.board.connections.domain.Connection;
import ec.kuador.board.connections.domain.ConnectionStore;
import ec.kuador.board.shared.json.Json;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Toda consulta lleva el espacio: estas tablas no pasan por el filtro de Hibernate. */
@Repository
class JdbcConnectionStore implements ConnectionStore {

    private static final String COLUMNS = "conn_key, name, url, header, secret, tools, allowed_writes, checked_at, oauth";

    private final JdbcClient jdbc;

    JdbcConnectionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Connection> all(UUID workspaceId) {
        return jdbc.sql("select " + COLUMNS + " from business_connection where workspace_id = ? order by name").param(workspaceId)
                .query(JdbcConnectionStore::map).list();
    }

    @Override
    public Optional<Connection> find(UUID workspaceId, String key) {
        return jdbc.sql("select " + COLUMNS + " from business_connection where workspace_id = ? and conn_key = ?").param(workspaceId).param(key)
                .query(JdbcConnectionStore::map).optional();
    }

    @Override
    public void save(UUID workspaceId, Connection c) {
        jdbc.sql("""
                insert into business_connection (workspace_id, conn_key, name, url, header, secret, tools, allowed_writes, checked_at, oauth)
                values (:ws, :key, :name, :url, :header, :secret, :tools, :writes, :checked, :oauth)
                on conflict (workspace_id, conn_key) do update set name = excluded.name, url = excluded.url, header = excluded.header,
                    secret = excluded.secret, tools = excluded.tools, allowed_writes = excluded.allowed_writes, checked_at = excluded.checked_at,
                    oauth = excluded.oauth""")
                .param("ws", workspaceId).param("key", c.key()).param("name", c.name()).param("url", c.url()).param("header", c.header())
                .param("secret", c.secret()).param("tools", Json.write(c.tools())).param("writes", Json.write(c.allowedWrites().stream().sorted().toList()))
                .param("checked", c.checkedAt() == null ? null : Timestamp.from(c.checkedAt())).param("oauth", c.oauth())
                .update();
    }

    @Override
    public void delete(UUID workspaceId, String key) {
        jdbc.sql("delete from business_connection where workspace_id = ? and conn_key = ?").param(workspaceId).param(key).update();
    }

    @Override
    public List<Use> uses(UUID workspaceId) {
        return jdbc.sql("select conn_key, project_key, roles from business_connection_board where workspace_id = ? order by project_key, conn_key")
                .param(workspaceId).query(JdbcConnectionStore::use).list();
    }

    @Override
    public List<Use> usesOf(UUID workspaceId, String projectKey) {
        return jdbc.sql("select conn_key, project_key, roles from business_connection_board where workspace_id = ? and project_key = ? order by conn_key")
                .param(workspaceId).param(projectKey).query(JdbcConnectionStore::use).list();
    }

    @Override
    public void saveUses(UUID workspaceId, String projectKey, List<Use> uses) {
        deleteUsesOfProject(workspaceId, projectKey);
        for (Use u : uses) {
            jdbc.sql("insert into business_connection_board (workspace_id, conn_key, project_key, roles) values (?, ?, ?, ?)")
                    .param(workspaceId).param(u.connection()).param(projectKey).param(Json.write(u.roles())).update();
        }
    }

    @Override
    public void deleteUsesOfProject(UUID workspaceId, String projectKey) {
        jdbc.sql("delete from business_connection_board where workspace_id = ? and project_key = ?").param(workspaceId).param(projectKey).update();
    }

    private static Connection map(ResultSet rs, int n) throws SQLException {
        Timestamp checked = rs.getTimestamp("checked_at");
        return new Connection(rs.getString("conn_key"), rs.getString("name"), rs.getString("url"), rs.getString("header"), rs.getString("secret"),
                Json.readList(rs.getString("tools"), Connection.ToolDef.class), new LinkedHashSet<>(Json.readList(rs.getString("allowed_writes"), String.class)),
                checked == null ? null : checked.toInstant(), rs.getString("oauth"));
    }

    private static Use use(ResultSet rs, int n) throws SQLException {
        return new Use(rs.getString("conn_key"), rs.getString("project_key"), Json.readList(rs.getString("roles"), String.class));
    }
}
