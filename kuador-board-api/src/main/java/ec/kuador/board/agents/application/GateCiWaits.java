package ec.kuador.board.agents.application;

import ec.kuador.board.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Tarjetas que esperan en una puerta a que termine el CI de su PR antes de que las revise el agente revisor. Una fila por
 * tarjeta: {@code since} es desde cuándo espera (no cambia mientras siga esperando) y {@code requesterId} la persona a quien
 * responde el revisor.
 */
@Component
class GateCiWaits {

    record Wait(UUID workspaceId, String issueKey, String projectKey, UUID agentId, UUID requesterId, Instant since) {}

    private final JdbcClient jdbc;

    GateCiWaits(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Desde cuándo espera la tarjeta (en el espacio actual), si espera. */
    Optional<Instant> since(String issueKey) {
        return jdbc.sql("select since from gate_ci_wait where workspace_id = ? and issue_key = ?")
                .params(TenantContext.require(), issueKey).query(Timestamp.class).optional().map(Timestamp::toInstant);
    }

    /** Empieza a esperar o sigue esperando (sin mover {@code since}). */
    void wait(String issueKey, String projectKey, UUID agentId, UUID requesterId, Instant now) {
        jdbc.sql("""
                insert into gate_ci_wait (workspace_id, issue_key, project_key, agent_id, requester_id, since, checked_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (workspace_id, issue_key) do update
                set agent_id = excluded.agent_id, requester_id = excluded.requester_id, checked_at = excluded.checked_at""")
                .params(TenantContext.require(), issueKey, projectKey, agentId, requesterId, Timestamp.from(now), Timestamp.from(now)).update();
    }

    void touch(String issueKey, Instant now) {
        jdbc.sql("update gate_ci_wait set checked_at = ? where workspace_id = ? and issue_key = ?")
                .params(Timestamp.from(now), TenantContext.require(), issueKey).update();
    }

    void done(String issueKey) {
        jdbc.sql("delete from gate_ci_wait where workspace_id = ? and issue_key = ?").params(TenantContext.require(), issueKey).update();
    }

    /** Las que no se miran desde {@code before}, de todos los espacios (lo llama el vigilante, como raíz). */
    List<Wait> due(Instant before, int limit) {
        return jdbc.sql("""
                select workspace_id, issue_key, project_key, agent_id, requester_id, since from gate_ci_wait
                where checked_at <= ? order by checked_at limit ?""")
                .params(Timestamp.from(before), limit)
                .query((rs, n) -> new Wait(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class), rs.getTimestamp(6).toInstant()))
                .list();
    }
}
