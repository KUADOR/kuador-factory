package ec.kuador.board.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.catalog.domain.CatalogType;
import ec.kuador.board.catalog.infrastructure.defaults.YamlFactoryCatalog;
import ec.kuador.board.catalog.infrastructure.platform.DbFactoryCatalog;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V48 sobre una plataforma que ya tenía catálogo: llegan la plantilla Marketing y el agente de Diseño (iguales a la
 * semilla), los manuales de Marketing y del Analista se actualizan donde nadie los cambió y la plantilla queda
 * recomendada en los paquetes.
 */
@Testcontainers
class MarketingTemplateMigrationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static final String WS = "00000000-0000-0000-0000-000000000001";
    static final String OTHER = "00000000-0000-0000-0000-000000000002";
    static final String OLD_MARKETING = "{\"key\":\"MARKETING\",\"name\":\"Marketing\",\"description\":\"antes\",\"instructions\":\"antes\",\"tools\":[],\"automation\":\"NONE\"}";

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    @Test
    void anExistingPlatformGetsTheMarketingTemplateAndTheDesigner() throws Exception {
        flyway("47").migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("insert into workspace (id, slug, name, created_at) values ('" + WS + "', 'flores', 'Flores', now()), ('" + OTHER + "', 'otro', 'Otro', now())");
            s.execute("insert into platform_catalog_entry (type, entry_key, payload, published_payload, updated_at) values "
                    + "('AGENT_ROLE', 'MARKETING', '" + OLD_MARKETING + "', '" + OLD_MARKETING + "', now()), "
                    + "('AGENT_POLICY', 'DEFAULT', '{\"key\":\"DEFAULT\",\"roles\":{\"QA\":{\"model\":\"m\",\"effort\":\"low\"}}}', "
                    + "'{\"key\":\"DEFAULT\",\"roles\":{\"QA\":{\"model\":\"m\",\"effort\":\"low\"}}}', now())");
            s.execute("insert into platform_catalog_release (version, added, updated, published_at) values (9, '[]', '[]', now())");
            s.execute("insert into catalog_entry (id, workspace_id, type, entry_key, payload, built_in, created_at, updated_at) values "
                    + "(gen_random_uuid(), '" + WS + "', 'AGENT_ROLE', 'MARKETING', '" + OLD_MARKETING + "', true, now(), now()), "
                    + "(gen_random_uuid(), '" + OTHER + "', 'AGENT_ROLE', 'MARKETING', '{\"key\":\"MARKETING\",\"name\":\"Nuestro marketing\"}', true, now(), now())");
        }

        flyway(null).migrate();

        YamlFactoryCatalog seed = new YamlFactoryCatalog();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            Object template = DbFactoryCatalog.parse(CatalogType.WORKFLOW_TEMPLATE, "MARKETING",
                    one(s, "select published_payload from platform_catalog_entry where type = 'WORKFLOW_TEMPLATE' and entry_key = 'MARKETING'"));
            assertThat(template).as("igual a la semilla").usingRecursiveComparison().ignoringFields("id", "builtIn", "version")
                    .isEqualTo(seed.workflowTemplates().stream().filter(t -> t.key().equals("MARKETING")).findFirst().orElseThrow());
            Object designer = DbFactoryCatalog.parse(CatalogType.AGENT_ROLE, "DESIGNER",
                    one(s, "select published_payload from platform_catalog_entry where type = 'AGENT_ROLE' and entry_key = 'DESIGNER'"));
            assertThat(designer).usingRecursiveComparison().ignoringFields("id", "builtIn", "version")
                    .isEqualTo(seed.roles().stream().filter(r -> r.key().equals("DESIGNER")).findFirst().orElseThrow());
            assertThat(one(s, "select extra from platform_catalog_entry where entry_key = 'DESIGNER'")).contains("Agente Diseño");

            assertThat(one(s, "select published_payload from platform_catalog_entry where type = 'AGENT_ROLE' and entry_key = 'MARKETING'"))
                    .contains("En «Brief»");
            assertThat(one(s, "select payload from catalog_entry where workspace_id = '" + WS + "' and entry_key = 'MARKETING'"))
                    .as("igual a la de fábrica: se actualiza").contains("En «Brief»");
            assertThat(one(s, "select payload from catalog_entry where workspace_id = '" + OTHER + "' and entry_key = 'MARKETING'"))
                    .as("modificada por su equipo: se respeta").contains("Nuestro marketing").doesNotContain("Brief");
            assertThat(one(s, "select published_payload from platform_catalog_entry where type = 'AGENT_POLICY'")).contains("DESIGNER", "QA");

            assertThat(one(s, "select added from platform_catalog_release where version = 10")).contains("MARKETING", "DESIGNER");
            assertThat(one(s, "select count(*) from catalog_package where not ('MARKETING' = any (templates))")).isEqualTo("0");
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String one(Statement s, String sql) throws Exception {
        try (ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
    }
}
