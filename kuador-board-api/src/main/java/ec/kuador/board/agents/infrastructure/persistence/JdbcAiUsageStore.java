package ec.kuador.board.agents.infrastructure.persistence;

import ec.kuador.board.agents.domain.AiUsageStore;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAiUsageStore implements AiUsageStore {

    private final JdbcClient jdbc;

    JdbcAiUsageStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Usage> usage(UUID workspaceId, Instant from, Instant to) {
        return jdbc.sql("""
                select agent_id, model, count(*), coalesce(sum(input_tokens), 0), coalesce(sum(output_tokens), 0),
                    coalesce(sum(cache_read_tokens), 0), coalesce(sum(cache_write_tokens), 0) from agent_run
                where workspace_id = ? and created_at >= ? and created_at < ? and input_tokens is not null and model is not null
                  and model not like 'local:%' and model <> 'claude-code'
                group by agent_id, model""")
                .params(workspaceId, Timestamp.from(from), Timestamp.from(to))
                .query((rs, n) -> new Usage(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6),
                        rs.getLong(7))).list();
    }

    @Override
    public List<Price> prices() {
        return jdbc.sql("select model, input_per_mtok, output_per_mtok from ai_model_price order by model")
                .query((rs, n) -> new Price(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3))).list();
    }

    @Override
    public void savePrice(Price p, Instant now) {
        jdbc.sql("""
                insert into ai_model_price (model, input_per_mtok, output_per_mtok, updated_at) values (?, ?, ?, ?)
                on conflict (model) do update set input_per_mtok = excluded.input_per_mtok, output_per_mtok = excluded.output_per_mtok,
                    updated_at = excluded.updated_at""")
                .params(p.model(), p.inputPerMTok(), p.outputPerMTok(), Timestamp.from(now)).update();
    }

    @Override
    public void deletePrice(String model) {
        jdbc.sql("delete from ai_model_price where model = ?").param(model).update();
    }

    @Override
    public Optional<BigDecimal> budget(UUID workspaceId) {
        return jdbc.sql("select monthly_budget_usd from workspace_ai where workspace_id = ?").param(workspaceId)
                .query((rs, n) -> Optional.ofNullable(rs.getBigDecimal(1))).optional().flatMap(o -> o);
    }

    @Override
    public void saveBudget(UUID workspaceId, BigDecimal budget) {
        jdbc.sql("update workspace_ai set monthly_budget_usd = ? where workspace_id = ?").params(budget, workspaceId).update();
    }

    @Override
    public List<Usage> issueUsage(UUID workspaceId, String issueKey) {
        return jdbc.sql("""
                select agent_id, model, count(*), coalesce(sum(input_tokens), 0), coalesce(sum(output_tokens), 0),
                    coalesce(sum(cache_read_tokens), 0), coalesce(sum(cache_write_tokens), 0) from agent_run
                where workspace_id = ? and issue_key = ? and input_tokens is not null and model is not null
                  and model not like 'local:%' and model <> 'claude-code'
                group by agent_id, model""")
                .params(workspaceId, issueKey)
                .query((rs, n) -> new Usage(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6),
                        rs.getLong(7))).list();
    }

    @Override
    public Optional<BigDecimal> cardCap(UUID workspaceId) {
        return jdbc.sql("select card_cap_usd from workspace_ai where workspace_id = ?").param(workspaceId)
                .query((rs, n) -> Optional.ofNullable(rs.getBigDecimal(1))).optional().flatMap(x -> x);
    }

    @Override
    public void saveCardCap(UUID workspaceId, BigDecimal cap) {
        jdbc.sql("update workspace_ai set card_cap_usd = ? where workspace_id = ?").params(cap, workspaceId).update();
    }

    @Override
    public java.util.Map<UUID, BigDecimal> agentBudgets(UUID workspaceId) {
        java.util.Map<UUID, BigDecimal> out = new java.util.LinkedHashMap<>();
        jdbc.sql("select agent_id, monthly_usd from agent_budget where workspace_id = ?").param(workspaceId)
                .query((rs, n) -> out.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2))).list();
        return out;
    }

    @Override
    public void saveAgentBudget(UUID workspaceId, UUID agentId, BigDecimal monthly) {
        if (monthly == null) {
            jdbc.sql("delete from agent_budget where agent_id = ? and workspace_id = ?").params(agentId, workspaceId).update();
            return;
        }
        jdbc.sql("""
                insert into agent_budget (agent_id, workspace_id, monthly_usd) values (?, ?, ?)
                on conflict (agent_id) do update set monthly_usd = excluded.monthly_usd""").params(agentId, workspaceId, monthly).update();
    }

    @Override
    public boolean markAlert(UUID workspaceId, String scope, String month, int level, Instant now) {
        return jdbc.sql("insert into budget_alert (workspace_id, scope, month, level, sent_at) values (?, ?, ?, ?, ?) on conflict do nothing")
                .params(workspaceId, scope, month, level, Timestamp.from(now)).update() == 1;
    }
}
