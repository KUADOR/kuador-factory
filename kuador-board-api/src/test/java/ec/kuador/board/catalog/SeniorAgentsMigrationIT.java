package ec.kuador.board.catalog;

import static org.assertj.core.api.Assertions.assertThat;

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
 * V60 sobre una plataforma que ya tenía catálogo y tableros: llega el rol UI/UX, los manuales se actualizan donde nadie
 * los cambió, el criterio de código entra en las reglas de agentes y los tableros de fábrica suman DevOps, UI/UX y el
 * Revisor de PR, con UI/UX antes del Arquitecto en Diseño (si Diseño sigue como vino).
 */
@Testcontainers
class SeniorAgentsMigrationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static final String WS = "00000000-0000-0000-0000-000000000001";
    static final String OTHER = "00000000-0000-0000-0000-000000000002";
    static final String OLD_ARCHITECT = "{\"key\":\"ARCHITECT\",\"name\":\"Arquitecto de software\",\"description\":\"antes\",\"instructions\":\"antes\",\"tools\":[],\"automation\":\"NONE\"}";
    static final String POLICY = "{\"key\":\"DEFAULT\",\"rules\":{\"epicPlan\":\"antes\"},\"roles\":{\"QA\":{\"model\":\"m\",\"effort\":\"low\"}}}";
    static final String DESIGN = "{\"column\":\"DESIGN\",\"roles\":[\"ARCHITECT\"],\"gate\":false,\"approvers\":[],\"rejectTo\":null}";
    static final String CUSTOM_DESIGN = "{\"column\":\"DESIGN\",\"roles\":[\"ARCHITECT\",\"BACKEND\"],\"gate\":false,\"approvers\":[],\"rejectTo\":null}";

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    @Test
    void anExistingPlatformGetsSeniorManualsUiUxAndTheFactoryTeam() throws Exception {
        flyway("59").migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("insert into workspace (id, slug, name, created_at) values ('" + WS + "', 'flores', 'Flores', now()), ('" + OTHER + "', 'otro', 'Otro', now())");
            s.execute("insert into platform_catalog_entry (type, entry_key, payload, published_payload, updated_at) values "
                    + "('AGENT_ROLE', 'ARCHITECT', '" + OLD_ARCHITECT + "', '" + OLD_ARCHITECT + "', now()), "
                    + "('AGENT_POLICY', 'DEFAULT', '" + POLICY + "', '" + POLICY + "', now())");
            s.execute("insert into platform_catalog_release (version, added, updated, published_at) values (11, '[]', '[]', now())");
            s.execute("insert into catalog_entry (id, workspace_id, type, entry_key, payload, built_in, created_at, updated_at) values "
                    + "(gen_random_uuid(), '" + WS + "', 'AGENT_ROLE', 'ARCHITECT', '" + OLD_ARCHITECT + "', true, now(), now()), "
                    + "(gen_random_uuid(), '" + OTHER + "', 'AGENT_ROLE', 'ARCHITECT', '{\"key\":\"ARCHITECT\",\"name\":\"Nuestro arquitecto\"}', true, now(), now()), "
                    + "(gen_random_uuid(), '" + WS + "', 'AGENT_POLICY', 'DEFAULT', '" + POLICY + "', true, now(), now())");
            s.execute("insert into project (id, workspace_id, project_key, name, kind, template_key, issue_types, created_at, stages) values "
                    + "(gen_random_uuid(), '" + WS + "', 'FAB', 'Fábrica', 'SOFTWARE', 'SOFTWARE_FACTORY', '[]', now(), '[" + DESIGN + "]'), "
                    + "(gen_random_uuid(), '" + OTHER + "', 'OWN', 'Propio', 'SOFTWARE', 'SOFTWARE_FACTORY', '[]', now(), '[" + CUSTOM_DESIGN + "]'), "
                    + "(gen_random_uuid(), '" + OTHER + "', 'MKT', 'Marketing', 'GENERAL', 'MARKETING', '[]', now(), '[]')");
            s.execute("insert into member (id, workspace_id, name, kind, role, avatar_color, access, created_at) values "
                    + "(gen_random_uuid(), '" + WS + "', 'Nuestro DevOps', 'AGENT', 'DEVOPS', '#000000', 'EDIT', now())");
        }

        flyway(null).migrate();

        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThat(one(s, "select published_payload from platform_catalog_entry where type = 'AGENT_ROLE' and entry_key = 'UX_UI'"))
                    .contains("Diseño UI/UX");
            assertThat(one(s, "select payload from catalog_entry where workspace_id = '" + WS + "' and entry_key = 'ARCHITECT'"))
                    .as("igual a la de fábrica: se actualiza").contains("Diagrama de la solución");
            assertThat(one(s, "select payload from catalog_entry where workspace_id = '" + OTHER + "' and entry_key = 'ARCHITECT'"))
                    .as("modificada por su equipo: se respeta").contains("Nuestro arquitecto");
            String policy = one(s, "select published_payload from platform_catalog_entry where type = 'AGENT_POLICY'");
            assertThat(policy).contains("SOLID", "Context7", "UX_UI", "QA", "```mermaid");
            assertThat(one(s, "select payload from catalog_entry where workspace_id = '" + WS + "' and type = 'AGENT_POLICY'"))
                    .as("su reparto seguía como el de fábrica").contains("```mermaid");
            assertThat(one(s, "select added from platform_catalog_release where version = 12")).contains("UX_UI");

            assertThat(one(s, "select count(*) from member where workspace_id = '" + WS + "' and role = 'DEVOPS'")).as("ya tenía uno").isEqualTo("1");
            assertThat(one(s, "select string_agg(m.role, ',' order by m.role) from project_agent a join member m on m.id = a.member_id"
                    + " where a.project_key = 'FAB'")).isEqualTo("DEVOPS,PR_REVIEWER,UX_UI");
            assertThat(one(s, "select count(*) from project_agent where project_key = 'MKT'")).as("otras plantillas no").isEqualTo("0");

            assertThat(one(s, "select stages from project where project_key = 'FAB'")).contains("\"epicFirst\": [\"UX_UI\"]", "DIAGRAM");
            assertThat(one(s, "select stages from project where project_key = 'OWN'")).as("Diseño propio: no se toca").doesNotContain("epicFirst");
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
