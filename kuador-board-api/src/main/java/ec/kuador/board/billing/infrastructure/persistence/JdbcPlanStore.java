package ec.kuador.board.billing.infrastructure.persistence;

import ec.kuador.board.billing.domain.Plan;
import ec.kuador.board.billing.domain.PlanStore;
import ec.kuador.board.billing.domain.Subscription;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL directo: los planes son de toda la plataforma y las licencias se listan para todos los espacios. */
@Repository
class JdbcPlanStore implements PlanStore {

    private static final String PLAN_COLUMNS = """
            code, name, tagline, price_monthly, currency, features, max_members, max_projects, factory, trial_days,
            highlighted, sort_order, active""";

    private final JdbcClient jdbc;

    JdbcPlanStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Plan> plans(boolean onlyActive) {
        return jdbc.sql("select " + PLAN_COLUMNS + " from plan" + (onlyActive ? " where active" : "") + " order by sort_order, code")
                .query((rs, n) -> plan(rs)).list();
    }

    @Override
    public Optional<Plan> plan(String code) {
        return jdbc.sql("select " + PLAN_COLUMNS + " from plan where code = ?").param(code).query((rs, n) -> plan(rs)).optional();
    }

    @Override
    public void savePlan(Plan p, Instant now) {
        jdbc.sql("""
                insert into plan (code, name, tagline, price_monthly, currency, features, max_members, max_projects, factory,
                                  trial_days, highlighted, sort_order, active, updated_at)
                values (?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (code) do update set name = excluded.name, tagline = excluded.tagline, price_monthly = excluded.price_monthly,
                    currency = excluded.currency, features = excluded.features, max_members = excluded.max_members,
                    max_projects = excluded.max_projects, factory = excluded.factory, trial_days = excluded.trial_days,
                    highlighted = excluded.highlighted, sort_order = excluded.sort_order, active = excluded.active, updated_at = excluded.updated_at""")
                .params(p.code(), p.name(), p.tagline(), p.priceMonthly(), p.currency(), arrayLiteral(p.features()), p.maxMembers(),
                        p.maxProjects(), p.factory(), p.trialDays(), p.highlighted(), p.sortOrder(), p.active(), Timestamp.from(now))
                .update();
    }

    @Override
    public Optional<Subscription> subscription(UUID workspaceId) {
        return jdbc.sql("select workspace_id, plan_code, status, trial_ends_at, paid_until, trial_used from workspace_subscription where workspace_id = ?")
                .param(workspaceId).query((rs, n) -> subscription(rs, 1)).optional();
    }

    @Override
    public void saveSubscription(Subscription s, Instant now) {
        jdbc.sql("""
                insert into workspace_subscription (workspace_id, plan_code, status, trial_ends_at, paid_until, trial_used, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (workspace_id) do update set plan_code = excluded.plan_code, status = excluded.status,
                    trial_ends_at = excluded.trial_ends_at, paid_until = excluded.paid_until, trial_used = excluded.trial_used,
                    updated_at = excluded.updated_at""")
                .params(s.workspaceId(), s.planCode(), s.status().name(), ts(s.trialEndsAt()), ts(s.paidUntil()), s.trialUsed(), Timestamp.from(now))
                .update();
    }

    @Override
    public List<WorkspaceLicense> licenses() {
        return jdbc.sql("""
                select w.id, w.slug, w.name, w.created_at, s.workspace_id, s.plan_code, s.status, s.trial_ends_at, s.paid_until, s.trial_used
                from workspace w left join workspace_subscription s on s.workspace_id = w.id
                order by w.created_at desc""")
                .query((rs, n) -> new WorkspaceLicense(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getObject(5) == null ? Optional.empty() : Optional.of(subscription(rs, 5))))
                .list();
    }

    private static Plan plan(ResultSet rs) throws SQLException {
        Array features = rs.getArray("features");
        return new Plan(rs.getString("code"), rs.getString("name"), rs.getString("tagline"), rs.getBigDecimal("price_monthly"),
                rs.getString("currency"), features == null ? List.of() : Arrays.asList((String[]) features.getArray()),
                (Integer) rs.getObject("max_members"), (Integer) rs.getObject("max_projects"), rs.getBoolean("factory"),
                rs.getInt("trial_days"), rs.getBoolean("highlighted"), rs.getInt("sort_order"), rs.getBoolean("active"));
    }

    private static Subscription subscription(ResultSet rs, int from) throws SQLException {
        return new Subscription(rs.getObject(from, UUID.class), rs.getString(from + 1), Subscription.Status.valueOf(rs.getString(from + 2)),
                instant(rs.getTimestamp(from + 3)), instant(rs.getTimestamp(from + 4)), rs.getBoolean(from + 5));
    }

    /** Literal de arreglo de Postgres ({"a","b"}) con comillas y barras escapadas. */
    static String arrayLiteral(List<String> values) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
