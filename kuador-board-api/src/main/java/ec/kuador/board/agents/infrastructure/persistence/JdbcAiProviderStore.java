package ec.kuador.board.agents.infrastructure.persistence;

import ec.kuador.board.agents.domain.AiProvider;
import ec.kuador.board.agents.domain.AiProviderStore;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAiProviderStore implements AiProviderStore {

    private static final String COLUMNS = "code, name, protocol, base_url, models, key_hint, active, sort_order";

    private final JdbcClient jdbc;

    JdbcAiProviderStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AiProvider> all() {
        return jdbc.sql("select " + COLUMNS + " from ai_provider order by sort_order, name").query(JdbcAiProviderStore::map).list();
    }

    @Override
    public Optional<AiProvider> find(String code) {
        return jdbc.sql("select " + COLUMNS + " from ai_provider where code = ?").param(code).query(JdbcAiProviderStore::map).optional();
    }

    @Override
    public void save(AiProvider p, Instant now) {
        jdbc.sql("""
                insert into ai_provider (code, name, protocol, base_url, models, key_hint, active, sort_order, updated_at)
                values (:code, :name, :protocol, :baseUrl, string_to_array(:models, E'\\n'), :keyHint, :active, :sortOrder, :now)
                on conflict (code) do update set name = excluded.name, protocol = excluded.protocol, base_url = excluded.base_url,
                    models = excluded.models, key_hint = excluded.key_hint, active = excluded.active, sort_order = excluded.sort_order,
                    updated_at = excluded.updated_at""")
                .param("code", p.code()).param("name", p.name()).param("protocol", p.protocol().name()).param("baseUrl", p.baseUrl())
                .param("models", p.models().isEmpty() ? null : String.join("\n", p.models())).param("keyHint", p.keyHint())
                .param("active", p.active()).param("sortOrder", p.sortOrder()).param("now", Timestamp.from(now))
                .update();
    }

    private static AiProvider map(ResultSet rs, int n) throws SQLException {
        Array models = rs.getArray("models");
        List<String> list = models == null ? List.of() : Arrays.stream((Object[]) models.getArray()).map(Object::toString).toList();
        return new AiProvider(rs.getString("code"), rs.getString("name"), AiProvider.Protocol.valueOf(rs.getString("protocol")), rs.getString("base_url"),
                list, rs.getString("key_hint"), rs.getBoolean("active"), rs.getInt("sort_order"));
    }
}
