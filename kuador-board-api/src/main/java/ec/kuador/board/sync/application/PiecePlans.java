package ec.kuador.board.sync.application;

import ec.kuador.board.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Las piezas planeadas de un proyecto nuevo (ver V47): se eligen antes de redactar los documentos, porque son la fuente
 * del stack, y el paso del repositorio arranca con ellas. Al armar el repo se borran: desde ahí mandan las piezas reales.
 */
@Component
class PiecePlans {

    private static final TypeReference<List<ComponentService.PieceSpec>> PIECES = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    PiecePlans(JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    Optional<ComponentService.PiecePlan> find(String projectKey, String subjectKey) {
        return jdbc.sql("select layout, pieces from piece_plan where workspace_id = ? and project_key = ? and subject_key = ?")
                .params(TenantContext.require(), projectKey, subjectKey)
                .query((rs, n) -> new ComponentService.PiecePlan(rs.getString(1), json.readValue(rs.getString(2), PIECES))).optional();
    }

    void save(String projectKey, String subjectKey, ComponentService.PiecePlan plan) {
        jdbc.sql("""
                insert into piece_plan (workspace_id, project_key, subject_key, layout, pieces, updated_at) values (?, ?, ?, ?, ?, ?)
                on conflict (workspace_id, project_key, subject_key) do update set layout = excluded.layout, pieces = excluded.pieces, updated_at = excluded.updated_at""")
                .params(TenantContext.require(), projectKey, subjectKey, plan.layout(), json.writeValueAsString(plan.pieces()), Timestamp.from(clock.instant()))
                .update();
    }

    void delete(String projectKey, String subjectKey) {
        jdbc.sql("delete from piece_plan where workspace_id = ? and project_key = ? and subject_key = ?")
                .params(TenantContext.require(), projectKey, subjectKey).update();
    }
}
