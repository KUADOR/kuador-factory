package ec.kuador.board.project.application;

import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** El objetivo de cada tablero (ver V42): una frase o dos que los agentes leen junto con cada tarjeta. */
@Component
class ProjectGoals {

    static final int MAX = 1000;

    private final JdbcClient jdbc;
    private final Clock clock;

    ProjectGoals(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Optional<String> find(String projectKey) {
        return jdbc.sql("select goal from project_goal where workspace_id = ? and project_key = ?")
                .params(TenantContext.require(), projectKey).query(String.class).optional();
    }

    /** Vacío borra el objetivo. */
    void save(String projectKey, String goal) {
        String clean = goal == null ? "" : goal.strip();
        if (clean.length() > MAX) throw new DomainException("El objetivo puede tener hasta " + MAX + " caracteres: que sea corto, una o dos frases.");
        if (clean.isEmpty()) {
            jdbc.sql("delete from project_goal where workspace_id = ? and project_key = ?").params(TenantContext.require(), projectKey).update();
            return;
        }
        jdbc.sql("""
                insert into project_goal (workspace_id, project_key, goal, updated_at) values (?, ?, ?, ?)
                on conflict (workspace_id, project_key) do update set goal = excluded.goal, updated_at = excluded.updated_at""")
                .params(TenantContext.require(), projectKey, clean, Timestamp.from(clock.instant())).update();
    }
}
