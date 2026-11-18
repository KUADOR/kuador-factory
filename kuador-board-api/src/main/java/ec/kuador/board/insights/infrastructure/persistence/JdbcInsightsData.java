package ec.kuador.board.insights.infrastructure.persistence;

import ec.kuador.board.insights.application.InsightsData;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL de lectura sobre el historial, las tarjetas y las ejecuciones: el dashboard no tiene tablas propias salvo su ajuste. */
@Repository
class JdbcInsightsData implements InsightsData {

    private final JdbcClient jdbc;

    JdbcInsightsData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Card> cards(UUID workspaceId, Collection<String> projectKeys) {
        if (projectKeys.isEmpty()) return List.of();
        return jdbc.sql("""
                select issue_key, project_key, title, type, status, story_points, assignee_id, supervisor_id, reporter_id, parent_key, rework_count,
                       created_at, updated_at
                from issue where workspace_id = :ws and project_key in (:projects)""")
                .param("ws", workspaceId).param("projects", projectKeys)
                .query((rs, n) -> new Card(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        (Integer) rs.getObject(6), rs.getObject(7, UUID.class), rs.getObject(8, UUID.class), rs.getObject(9, UUID.class), rs.getString(10),
                        rs.getInt(11), rs.getTimestamp(12).toInstant(), rs.getTimestamp(13).toInstant()))
                .list();
    }

    @Override
    public List<Event> events(UUID workspaceId, Collection<String> projectKeys, Instant since) {
        if (projectKeys.isEmpty()) return List.of();
        return jdbc.sql("""
                select issue_key, project_key, actor_id, type, from_value, to_value, occurred_at
                from activity
                where workspace_id = :ws and project_key in (:projects) and occurred_at >= :since and issue_key is not null
                  and type in ('CREATED', 'MOVED', 'ASSIGNED')
                order by issue_key, occurred_at""")
                .param("ws", workspaceId).param("projects", projectKeys).param("since", Timestamp.from(since))
                .query((rs, n) -> new Event(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getTimestamp(7).toInstant()))
                .list();
    }

    @Override
    public List<Run> runs(UUID workspaceId, Collection<String> projectKeys, Instant since) {
        if (projectKeys.isEmpty()) return List.of();
        return jdbc.sql("""
                select agent_id, issue_key, project_key, status, model, coalesce(input_tokens, 0), coalesce(output_tokens, 0),
                       coalesce(cache_read_tokens, 0), coalesce(cache_write_tokens, 0), created_at, finished_at, requested_by
                from agent_run where workspace_id = :ws and project_key in (:projects) and created_at >= :since""")
                .param("ws", workspaceId).param("projects", projectKeys).param("since", Timestamp.from(since))
                .query((rs, n) -> {
                    Timestamp finished = rs.getTimestamp(11);
                    return new Run(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6),
                            rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getTimestamp(10).toInstant(), finished == null ? null : finished.toInstant(),
                            rs.getObject(12, UUID.class));
                })
                .list();
    }

    @Override
    public Map<String, Price> prices() {
        Map<String, Price> out = new LinkedHashMap<>();
        jdbc.sql("select model, input_per_mtok, output_per_mtok from ai_model_price")
                .query((rs, n) -> out.put(rs.getString(1), new Price(rs.getBigDecimal(2), rs.getBigDecimal(3)))).list();
        return out;
    }

    @Override
    public Optional<BigDecimal> hoursPerPoint(UUID workspaceId) {
        return jdbc.sql("select hours_per_point from insights_settings where workspace_id = ?").param(workspaceId).query(BigDecimal.class).optional();
    }

    @Override
    public void saveHoursPerPoint(UUID workspaceId, BigDecimal hours, Instant now) {
        jdbc.sql("""
                insert into insights_settings (workspace_id, hours_per_point, updated_at) values (?, ?, ?)
                on conflict (workspace_id) do update set hours_per_point = excluded.hours_per_point, updated_at = excluded.updated_at""")
                .params(workspaceId, hours, Timestamp.from(now)).update();
    }
}
