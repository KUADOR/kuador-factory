package ec.kuador.board.audit.infrastructure.persistence;

import ec.kuador.board.audit.domain.AuditEntry;
import ec.kuador.board.audit.domain.AuditStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAuditStore implements AuditStore {

    private final JdbcClient jdbc;

    JdbcAuditStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(AuditEntry e) {
        jdbc.sql("""
                insert into audit_log (id, workspace_id, actor_id, actor_name, actor_kind, action, category, target, status, ip, user_agent, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(e.id(), e.workspaceId(), e.actorId(), e.actorName(), e.actorKind(), e.action(), e.category().name(), e.target(), e.status(),
                        e.ip(), e.userAgent(), Timestamp.from(e.occurredAt()))
                .update();
    }

    @Override
    public List<AuditEntry> find(Query q) {
        StringBuilder sql = new StringBuilder("select * from audit_log where true");
        List<Object> params = new ArrayList<>();
        if (!q.allWorkspaces()) {
            sql.append(" and workspace_id = ?");
            params.add(q.workspaceId());
        }
        if (q.category() != null) {
            sql.append(" and category = ?");
            params.add(q.category().name());
        }
        if (q.actorId() != null) {
            sql.append(" and actor_id = ?");
            params.add(q.actorId());
        }
        if (q.text() != null && !q.text().isBlank()) {
            sql.append(" and (action ilike ? or target ilike ? or actor_name ilike ? or ip ilike ?)");
            String like = "%" + q.text().strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            for (int i = 0; i < 4; i++) params.add(like);
        }
        if (q.from() != null) {
            sql.append(" and occurred_at >= ?");
            params.add(Timestamp.from(q.from()));
        }
        if (q.before() != null) {
            sql.append(" and occurred_at < ?");
            params.add(Timestamp.from(q.before()));
        }
        sql.append(" order by occurred_at desc limit ?");
        params.add(q.limit());
        return jdbc.sql(sql.toString()).params(params).query(JdbcAuditStore::map).list();
    }

    private static AuditEntry map(ResultSet rs, int n) throws SQLException {
        return new AuditEntry(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class), rs.getObject("actor_id", UUID.class),
                rs.getString("actor_name"), rs.getString("actor_kind"), rs.getString("action"), AuditEntry.Category.valueOf(rs.getString("category")),
                rs.getString("target"), rs.getInt("status"), rs.getString("ip"), rs.getString("user_agent"), rs.getTimestamp("occurred_at").toInstant());
    }
}
