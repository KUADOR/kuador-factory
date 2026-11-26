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
 * V66 sobre tableros que ya tenían personas y agentes: cada tablero queda con un equipo de su mismo nombre, con la misma
 * gente. Nadie gana ni pierde acceso y nadie rearma nada.
 */
@Testcontainers
class TeamsMigrationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static final String WS = "00000000-0000-0000-0000-000000000001";
    static final String ANA = "00000000-0000-0000-0000-0000000000a1";
    static final String LUIS = "00000000-0000-0000-0000-0000000000a2";
    static final String BOT = "00000000-0000-0000-0000-0000000000b1";

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    @Test
    void everyBoardGetsATeamWithTheSamePeopleAndAgents() throws Exception {
        flyway("65").migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("insert into workspace (id, slug, name, created_at) values ('" + WS + "', 'flores', 'Flores', now())");
            s.execute("insert into project (id, workspace_id, project_key, name, kind, template_key, issue_types, created_at) values "
                    + "(gen_random_uuid(), '" + WS + "', 'DEV', 'Desarrollo', 'SOFTWARE', 'SOFTWARE', '[]', now() - interval '2 day'), "
                    + "(gen_random_uuid(), '" + WS + "', 'MKT', 'Marketing', 'GENERAL', 'TASKS', '[]', now() - interval '1 day'), "
                    + "(gen_random_uuid(), '" + WS + "', 'MKT2', 'marketing', 'GENERAL', 'TASKS', '[]', now())");
            s.execute("insert into member (id, workspace_id, name, kind, role, avatar_color, access, created_at) values "
                    + "('" + ANA + "', '" + WS + "', 'Ana', 'HUMAN', 'DEV', '#000000', 'EDIT', now()), "
                    + "('" + LUIS + "', '" + WS + "', 'Luis', 'HUMAN', 'DEV', '#000000', 'VIEW', now()), "
                    + "('" + BOT + "', '" + WS + "', 'Agente', 'AGENT', 'BACKEND', '#000000', 'EDIT', now())");
            s.execute("insert into project_member (workspace_id, project_key, member_id) values ('" + WS + "', 'DEV', '" + ANA + "'), "
                    + "('" + WS + "', 'MKT', '" + ANA + "'), ('" + WS + "', 'MKT', '" + LUIS + "')");
            s.execute("insert into project_agent (workspace_id, project_key, member_id) values ('" + WS + "', 'DEV', '" + BOT + "')");
        }

        flyway(null).migrate();

        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThat(one(s, "select count(*) from project where team_id is null")).isEqualTo("0");
            assertThat(one(s, "select string_agg(t.name, ',' order by p.project_key) from project p join team t on t.id = p.team_id"))
                    .as("mismo nombre; el repetido lleva su clave").isEqualTo("Desarrollo,Marketing,marketing (MKT2)");
            assertThat(members(s, "DEV")).isEqualTo("Agente,Ana");
            assertThat(members(s, "MKT")).isEqualTo("Ana,Luis");
            assertThat(members(s, "MKT2")).isNull();
            assertThat(one(s, "select count(distinct team_id) from project")).as("un equipo por tablero").isEqualTo("3");
        }
    }

    private static String members(Statement s, String board) throws Exception {
        return one(s, "select string_agg(m.name, ',' order by m.name) from project p join team_member tm on tm.team_id = p.team_id"
                + " join member m on m.id = tm.member_id where p.project_key = '" + board + "'");
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
