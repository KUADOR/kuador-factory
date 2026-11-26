package ec.kuador.board.project;

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
 * V67 sobre tableros que ya guardaban cosas de un proyecto: lo que guardó uno solo queda en el proyecto, lo que varios
 * guardaron igual se junta, y si guardaron cosas distintas el proyecto se parte en dos sin perder nada.
 */
@Testcontainers
class ProjectResourcesMigrationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static final String WS = "00000000-0000-0000-0000-000000000001";

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    @Test
    void boardsThatSavedTheSameAreJoinedAndBoardsThatSavedOtherThingsGetTheirOwnCopy() throws Exception {
        flyway("66").migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("insert into workspace (id, slug, name, created_at) values ('" + WS + "', 'flores', 'Flores', now())");
            s.execute("insert into project (id, workspace_id, project_key, name, kind, template_key, issue_types, created_at, capabilities) values "
                    + "(gen_random_uuid(), '" + WS + "', 'DEV', 'Desarrollo', 'SOFTWARE', 'SOFTWARE', '[]', now(), null), "
                    + "(gen_random_uuid(), '" + WS + "', 'SOP', 'Soporte', 'SOFTWARE', 'SOFTWARE', '[]', now(), null), "
                    + "(gen_random_uuid(), '" + WS + "', 'QA', 'Calidad', 'SOFTWARE', 'SOFTWARE', '[]', now(), null), "
                    + "(gen_random_uuid(), '" + WS + "', 'MKT', 'Marketing', 'GENERAL', 'TASKS', '[]', now(), null), "
                    + "(gen_random_uuid(), '" + WS + "', 'CLI', 'Clínica', 'GENERAL', 'TASKS', '[]', now(), '[\"DOCUMENTS\"]')");
            s.execute("insert into subject (id, workspace_id, subject_key, name, created_at) values "
                    + "(gen_random_uuid(), '" + WS + "', 'GYM', 'Gimnasio', now()), (gen_random_uuid(), '" + WS + "', 'SAL', 'Salud', now())");
            s.execute("insert into board_subject (workspace_id, project_key, subject_key, position, added_at, methodology) values "
                    + "('" + WS + "', 'DEV', 'GYM', 0, now() - interval '4 day', 'ODD'), "
                    + "('" + WS + "', 'SOP', 'GYM', 0, now() - interval '3 day', 'ODD'), "
                    + "('" + WS + "', 'QA', 'GYM', 0, now() - interval '2 day', 'SDD'), "
                    + "('" + WS + "', 'MKT', 'GYM', 0, now() - interval '1 day', null), "
                    + "('" + WS + "', 'CLI', 'SAL', 0, now(), 'MANUAL')");
            // Desarrollo y Soporte guardaron lo mismo; Calidad, otro repositorio.
            s.execute("insert into project_repository (id, workspace_id, project_key, subject_key, kind, location, branch, secret, linked_at) values "
                    + "(gen_random_uuid(), '" + WS + "', 'DEV', 'GYM', 'GITHUB_APP', 'acme/gym', 'main', '1', now()), "
                    + "(gen_random_uuid(), '" + WS + "', 'SOP', 'GYM', 'GITHUB_APP', 'acme/gym', 'main', '2', now()), "
                    + "(gen_random_uuid(), '" + WS + "', 'QA', 'GYM', 'GITHUB_APP', 'acme/otro', 'main', '3', now())");
            for (String board : new String[] {"DEV", "SOP", "QA"}) {
                String id = java.util.UUID.randomUUID().toString();
                s.execute("insert into document (id, workspace_id, project_key, subject_key, kind, title, path, current_version, created_at, updated_at) values "
                        + "('" + id + "', '" + WS + "', '" + board + "', 'GYM', 'PRODUCT', 'Producto', 'PRODUCT.md', 1, now(), now())");
                s.execute("insert into document_version (document_id, version, workspace_id, content, created_at) values ('" + id + "', 1, '" + WS
                        + "', '" + (board.equals("QA") ? "Otro producto" : "El gimnasio") + "', now())");
            }
            s.execute("insert into issue (id, workspace_id, issue_key, project_key, title, type, status, priority, position, created_at, updated_at, subject_key) values "
                    + "(gen_random_uuid(), '" + WS + "', 'QA-1', 'QA', 'Probar', 'TASK', 'TODO', 'MEDIUM', 0, now(), now(), 'GYM'), "
                    + "(gen_random_uuid(), '" + WS + "', 'MKT-1', 'MKT', 'Campaña', 'TASK', 'TODO', 'MEDIUM', 0, now(), now(), 'GYM')");
        }

        flyway(null).migrate();

        try (Connection c = connect(); Statement s = c.createStatement()) {
            // El gimnasio queda con lo de Desarrollo (igual a lo de Soporte), una sola vez.
            assertThat(one(s, "select methodology from subject where subject_key = 'GYM'")).isEqualTo("ODD");
            assertThat(one(s, "select string_agg(location, ',') from project_repository where subject_key = 'GYM'")).isEqualTo("acme/gym");
            assertThat(one(s, "select count(*) from document where subject_key = 'GYM' and issue_key is null")).isEqualTo("1");
            assertThat(one(s, "select string_agg(project_key, ',' order by project_key) from board_subject where subject_key = 'GYM'")).isEqualTo("DEV,MKT,SOP");
            // Calidad guardó otra cosa: recibe su copia, con lo suyo y su tarjeta.
            String copy = one(s, "select subject_key from subject where name = 'Gimnasio (Calidad)'");
            assertThat(copy).isEqualTo("GYM2");
            assertThat(one(s, "select methodology from subject where subject_key = 'GYM2'")).isEqualTo("SDD");
            assertThat(one(s, "select location from project_repository where subject_key = 'GYM2'")).isEqualTo("acme/otro");
            assertThat(one(s, "select v.content from document d join document_version v on v.document_id = d.id where d.subject_key = 'GYM2'")).isEqualTo("Otro producto");
            assertThat(one(s, "select subject_key from board_subject where project_key = 'QA'")).isEqualTo("GYM2");
            assertThat(one(s, "select subject_key from issue where issue_key = 'QA-1'")).isEqualTo("GYM2");
            assertThat(one(s, "select subject_key from issue where issue_key = 'MKT-1'")).isEqualTo("GYM");
            // Marketing no usaba manual: sigue sin los documentos del proyecto; la clínica, que tenía uno, los usa.
            assertThat(one(s, "select coalesce(capabilities, '') from project where project_key = 'MKT'")).doesNotContain("PROJECT_DOCS");
            assertThat(one(s, "select capabilities from project where project_key = 'CLI'")).contains("PROJECT_DOCS");
            assertThat(one(s, "select methodology from subject where subject_key = 'SAL'")).isEqualTo("MANUAL");
            // Borrar un tablero ya no se lleva lo del proyecto.
            s.execute("delete from project where project_key = 'SOP'");
            assertThat(one(s, "select count(*) from project_repository where subject_key = 'GYM'")).isEqualTo("1");
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
