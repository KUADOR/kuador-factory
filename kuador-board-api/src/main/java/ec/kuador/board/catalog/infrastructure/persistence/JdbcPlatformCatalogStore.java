package ec.kuador.board.catalog.infrastructure.persistence;

import ec.kuador.board.catalog.domain.CatalogType;
import ec.kuador.board.catalog.domain.PlatformCatalogStore;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/** SQL directo: el catálogo de la plataforma es de todos los espacios. */
@Repository
class JdbcPlatformCatalogStore implements PlatformCatalogStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    JdbcPlatformCatalogStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isEmpty() {
        return jdbc.sql("select count(*) from platform_catalog_entry").query(Long.class).single() == 0
                && jdbc.sql("select count(*) from platform_catalog_release").query(Long.class).single() == 0;
    }

    @Override
    public List<Row> rows() {
        return jdbc.sql("select type, entry_key, payload, published_payload, extra from platform_catalog_entry order by type, entry_key")
                .query((rs, n) -> row(rs)).list();
    }

    @Override
    public Optional<Row> row(CatalogType type, String key) {
        return jdbc.sql("select type, entry_key, payload, published_payload, extra from platform_catalog_entry where type = ? and entry_key = ?")
                .params(type.name(), key).query((rs, n) -> row(rs)).optional();
    }

    @Override
    public void save(Row r, Instant now) {
        jdbc.sql("""
                insert into platform_catalog_entry (type, entry_key, payload, published_payload, extra, updated_at) values (?, ?, ?, ?, ?, ?)
                on conflict (type, entry_key) do update set payload = excluded.payload, published_payload = excluded.published_payload,
                    extra = excluded.extra, updated_at = excluded.updated_at""")
                .params(r.type().name(), r.key(), r.payload(), r.published(), r.extra(), Timestamp.from(now)).update();
    }

    @Override
    public void delete(CatalogType type, String key) {
        jdbc.sql("delete from platform_catalog_entry where type = ? and entry_key = ?").params(type.name(), key).update();
    }

    @Override
    public List<Release> releases() {
        return jdbc.sql("select version, added, updated, published_at from platform_catalog_release order by version")
                .query((rs, n) -> new Release(rs.getInt(1), entries(rs.getString(2)), entries(rs.getString(3)), rs.getTimestamp(4).toInstant())).list();
    }

    @Override
    public void saveRelease(Release r) {
        jdbc.sql("insert into platform_catalog_release (version, added, updated, published_at) values (?, ?, ?, ?)")
                .params(r.version(), json(r.added()), json(r.updated()), Timestamp.from(r.publishedAt())).update();
    }

    @Override
    public List<Package> packages(boolean onlyActive) {
        return jdbc.sql("select code, name, description, templates, starter_template, starter_key, starter_name, sort_order, active from catalog_package"
                + (onlyActive ? " where active" : "") + " order by sort_order, code").query((rs, n) -> pkg(rs)).list();
    }

    @Override
    public Optional<Package> pkg(String code) {
        return jdbc.sql("select code, name, description, templates, starter_template, starter_key, starter_name, sort_order, active from catalog_package where code = ?")
                .param(code).query((rs, n) -> pkg(rs)).optional();
    }

    @Override
    public void savePackage(Package p, Instant now) {
        String templates = "{" + String.join(",", p.templates()) + "}";
        jdbc.sql("""
                insert into catalog_package (code, name, description, templates, starter_template, starter_key, starter_name, sort_order, active, updated_at)
                values (?, ?, ?, ?::text[], ?, ?, ?, ?, ?, ?)
                on conflict (code) do update set name = excluded.name, description = excluded.description, templates = excluded.templates,
                    starter_template = excluded.starter_template, starter_key = excluded.starter_key, starter_name = excluded.starter_name,
                    sort_order = excluded.sort_order, active = excluded.active, updated_at = excluded.updated_at""")
                .params(p.code(), p.name(), p.description(), templates, p.starterTemplate(), p.starterKey(), p.starterName(), p.sortOrder(), p.active(),
                        Timestamp.from(now))
                .update();
    }

    @Override
    public void choosePackage(java.util.UUID workspaceId, String code, Instant now) {
        jdbc.sql("insert into workspace_package (workspace_id, package_code, chosen_at) values (?, ?, ?) "
                + "on conflict (workspace_id) do update set package_code = excluded.package_code, chosen_at = excluded.chosen_at")
                .params(workspaceId, code, Timestamp.from(now)).update();
    }

    @Override
    public Optional<String> packageOf(java.util.UUID workspaceId) {
        return jdbc.sql("select package_code from workspace_package where workspace_id = ?").param(workspaceId).query(String.class).optional();
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(CatalogType.valueOf(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5));
    }

    private static Package pkg(ResultSet rs) throws SQLException {
        Array templates = rs.getArray("templates");
        return new Package(rs.getString("code"), rs.getString("name"), rs.getString("description"),
                templates == null ? List.of() : Arrays.asList((String[]) templates.getArray()), rs.getString("starter_template"), rs.getString("starter_key"),
                rs.getString("starter_name"), rs.getInt("sort_order"), rs.getBoolean("active"));
    }

    private static List<Map.Entry<CatalogType, String>> entries(String json) {
        JsonNode node = JSON.readTree(json == null ? "[]" : json);
        return node.valueStream().map(n -> Map.entry(CatalogType.valueOf(n.get("type").asString()), n.get("key").asString())).toList();
    }

    private static String json(List<Map.Entry<CatalogType, String>> list) {
        ArrayNode arr = JSON.createArrayNode();
        list.forEach(e -> arr.addObject().put("type", e.getKey().name()).put("key", e.getValue()));
        return arr.toString();
    }
}
