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
                select agent_id, model, count(*), coalesce(sum(input_tokens), 0), coalesce(sum(output_tokens), 0) from agent_run
                where workspace_id = ? and created_at >= ? and created_at < ? and input_tokens is not null and model is not null
                  and model not like 'local:%' and model <> 'claude-code'
                group by agent_id, model""")
                .params(workspaceId, Timestamp.from(from), Timestamp.from(to))
                .query((rs, n) -> new Usage(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5))).list();
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
}
