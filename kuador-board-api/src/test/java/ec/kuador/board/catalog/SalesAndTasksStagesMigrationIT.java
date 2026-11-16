package ec.kuador.board.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V30 sobre datos de antes: los tableros de Ventas y Tareas reciben sus columnas y etapas nuevas sin mover tarjetas;
 * un tablero rediseñado por su equipo y una plantilla modificada por un espacio no se tocan.
 */
@Testcontainers
class SalesAndTasksStagesMigrationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static final String WS = "00000000-0000-0000-0000-000000000001";
    static final String OTHER = "00000000-0000-0000-0000-000000000002";

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    @Test
    void existingBoardsGetTheNewColumnsAndStages() throws Exception {
        flyway("29").migrate();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = c.createStatement()) {
            s.execute("insert into workspace (id, slug, name, created_at) values ('" + WS + "', 'flores', 'Flores', now()), ('" + OTHER + "', 'otro', 'Otro', now())");
            board(s, WS, "VEN", "SALES", List.of("PROSPECT:TODO", "CONTACTED:IN_PROGRESS", "PROPOSAL:IN_PROGRESS", "NEGOTIATION:IN_PROGRESS", "WON:DONE", "LOST:DONE"));
            board(s, WS, "OPE", "TASKS", List.of("TODO:TODO", "DOING:IN_PROGRESS", "DONE:DONE"));
            // Un tablero de tareas que su equipo rediseñó (sin «Haciendo»): no se toca.
            board(s, OTHER, "MIO", "TASKS", List.of("TODO:TODO", "WAITING:IN_PROGRESS", "DONE:DONE"));
            s.execute("insert into issue (id, workspace_id, issue_key, project_key, title, type, status, priority, position, created_at, updated_at) "
                    + "values (gen_random_uuid(), '" + WS + "', 'VEN-1', 'VEN', 'Clínica Norte', 'TASK', 'PROPOSAL', 'HIGH', 0, now(), now())");
            // Catálogo: lo publicado y la copia de cada espacio (una igual, otra modificada por su equipo).
            s.execute("insert into platform_catalog_entry (type, entry_key, payload, published_payload, updated_at) values "
                    + "('WORKFLOW_TEMPLATE', 'SALES', '{\"key\":\"SALES\",\"name\":\"Ventas\"}', '{\"key\":\"SALES\",\"name\":\"Ventas\"}', now())");
            s.execute("insert into platform_catalog_release (version, added, updated, published_at) values (5, '[]', '[]', now())");
            s.execute("insert into catalog_entry (id, workspace_id, type, entry_key, payload, built_in, created_at, updated_at) values "
                    + "(gen_random_uuid(), '" + WS + "', 'WORKFLOW_TEMPLATE', 'SALES', '{\"name\": \"Ventas\", \"key\": \"SALES\"}', true, now(), now()), "
                    + "(gen_random_uuid(), '" + OTHER + "', 'WORKFLOW_TEMPLATE', 'SALES', '{\"key\":\"SALES\",\"name\":\"Ventas propias\"}', true, now(), now())");
        }

        flyway(null).migrate();

        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = c.createStatement()) {
            assertThat(columns(s, WS, "VEN")).containsExactly("PROSPECT", "RESEARCH", "CONTACTED", "PROPOSAL_DRAFT", "PROPOSAL_REVIEW", "PROPOSAL",
                    "NEGOTIATION", "WON", "LOST");
            assertThat(one(s, "select stages from project where project_key = 'VEN'")).contains("\"RESEARCH\"", "\"MARKETING\"", "\"rejectTo\":\"PROPOSAL_DRAFT\"");
            assertThat(one(s, "select status from issue where issue_key = 'VEN-1'")).as("las tarjetas no se mueven").isEqualTo("PROPOSAL");
            assertThat(columns(s, WS, "OPE")).containsExactly("TODO", "DOING", "REVIEW", "DONE");
            assertThat(one(s, "select stages from project where project_key = 'OPE'")).contains("\"REVIEW\"", "\"gate\":true");
            assertThat(columns(s, OTHER, "MIO")).as("rediseñado por su equipo").containsExactly("TODO", "WAITING", "DONE");
            assertThat(one(s, "select stages from project where project_key = 'MIO'")).isEqualTo("[]");

            assertThat(one(s, "select published_payload from platform_catalog_entry where entry_key = 'SALES'")).contains("PROPOSAL_REVIEW");
            assertThat(one(s, "select payload from catalog_entry where workspace_id = '" + WS + "' and entry_key = 'SALES'"))
                    .as("igual a la de fábrica: se actualiza").contains("PROPOSAL_REVIEW");
            assertThat(one(s, "select payload from catalog_entry where workspace_id = '" + OTHER + "' and entry_key = 'SALES'"))
                    .as("modificada por su equipo: se respeta").contains("Ventas propias").doesNotContain("PROPOSAL_REVIEW");
            assertThat(one(s, "select updated from platform_catalog_release where version = 6")).contains("SALES", "ANALYST");
        }
    }

    @Test
    void aFreshDatabaseIsLeftForTheYamlSeed() throws Exception {
        // En una base nueva el catálogo de la plataforma se siembra del YAML al arrancar, solo si no hay versiones.
        PostgreSQLContainer fresh = new PostgreSQLContainer("postgres:18-alpine");
        fresh.start();
        try {
            Flyway.configure().dataSource(fresh.getJdbcUrl(), fresh.getUsername(), fresh.getPassword()).locations("classpath:db/migration").load().migrate();
            try (Connection c = DriverManager.getConnection(fresh.getJdbcUrl(), fresh.getUsername(), fresh.getPassword()); Statement s = c.createStatement()) {
                assertThat(one(s, "select count(*) from platform_catalog_release")).isEqualTo("0");
            }
        } finally {
            fresh.stop();
        }
    }

    private static void board(Statement s, String ws, String key, String template, List<String> columns) throws Exception {
        s.execute("insert into project (id, workspace_id, project_key, name, kind, template_key, issue_types, created_at) values (gen_random_uuid(), '"
                + ws + "', '" + key + "', '" + key + "', 'GENERAL', '" + template + "', '[]', now())");
        for (int i = 0; i < columns.size(); i++) {
            String[] col = columns.get(i).split(":");
            s.execute("insert into board_column (id, workspace_id, project_key, column_key, name, category, position) values (gen_random_uuid(), '"
                    + ws + "', '" + key + "', '" + col[0] + "', '" + col[0] + "', '" + col[1] + "', " + i + ")");
        }
    }

    private static List<String> columns(Statement s, String ws, String key) throws Exception {
        List<String> out = new ArrayList<>();
        try (ResultSet r = s.executeQuery("select column_key from board_column where workspace_id = '" + ws + "' and project_key = '" + key
                + "' order by position")) {
            while (r.next()) out.add(r.getString(1));
        }
        return out;
    }

    private static String one(Statement s, String sql) throws Exception {
        try (ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
    }
}
