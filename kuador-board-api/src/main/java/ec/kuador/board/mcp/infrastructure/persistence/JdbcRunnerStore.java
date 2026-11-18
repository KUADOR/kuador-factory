package ec.kuador.board.mcp.infrastructure.persistence;

import ec.kuador.board.mcp.application.RunnerStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL directo: los préstamos vencidos se revisan para todos los espacios a la vez. */
@Repository
class JdbcRunnerStore implements RunnerStore {

    private static final String LEASE_COLUMNS = "id, workspace_id, issue_key, epic_key, status, machine_id, member_id, agent_id, started_at, expires_at, released_at, outcome";
    private static final String MACHINE_COLUMNS = "id, workspace_id, member_id, name, os, tool, roles, capacity, paused, version, last_seen, created_at";

    private final JdbcClient jdbc;

    JdbcRunnerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- Máquinas ----

    @Override
    public void saveMachine(Machine m) {
        jdbc.sql("""
                insert into runner_machine (id, workspace_id, member_id, name, os, tool, roles, capacity, paused, version, last_seen, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (id) do update set name = excluded.name, os = excluded.os, tool = excluded.tool, roles = excluded.roles,
                    capacity = excluded.capacity, paused = excluded.paused, version = excluded.version, last_seen = excluded.last_seen""")
                .params(m.id(), m.workspaceId(), m.memberId(), m.name(), m.os(), m.tool(), String.join(",", m.roles()), m.capacity(), m.paused(),
                        m.version(), Timestamp.from(m.lastSeen()), Timestamp.from(m.createdAt()))
                .update();
    }

    @Override
    public Optional<Machine> machine(UUID id) {
        return jdbc.sql("select " + MACHINE_COLUMNS + " from runner_machine where id = ?").param(id).query(JdbcRunnerStore::machine).optional();
    }

    @Override
    public List<Machine> machines(UUID workspaceId) {
        return jdbc.sql("select " + MACHINE_COLUMNS + " from runner_machine where workspace_id = ? order by created_at")
                .param(workspaceId).query(JdbcRunnerStore::machine).list();
    }

    @Override
    public void deleteMachine(UUID id) {
        jdbc.sql("delete from runner_machine where id = ?").param(id).update();
    }

    private static Machine machine(ResultSet rs, int n) throws SQLException {
        String roles = rs.getString(7);
        return new Machine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                rs.getString(6), roles.isBlank() ? List.of() : Arrays.asList(roles.split(",")), rs.getInt(8), rs.getBoolean(9), rs.getString(10),
                rs.getTimestamp(11).toInstant(), rs.getTimestamp(12).toInstant());
    }

    // ---- Préstamos ----

    @Override
    public boolean insertLease(Lease l) {
        return jdbc.sql("""
                insert into runner_lease (id, workspace_id, issue_key, epic_key, status, machine_id, member_id, agent_id, started_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (workspace_id, issue_key) where released_at is null do nothing""")
                .params(l.id(), l.workspaceId(), l.issueKey(), l.epicKey(), l.status(), l.machineId(), l.memberId(), l.agentId(),
                        Timestamp.from(l.startedAt()), Timestamp.from(l.expiresAt()))
                .update() == 1;
    }

    @Override
    public List<Lease> activeLeases(UUID workspaceId) {
        return jdbc.sql("select " + LEASE_COLUMNS + " from runner_lease where workspace_id = ? and released_at is null")
                .param(workspaceId).query(JdbcRunnerStore::lease).list();
    }

    @Override
    public Optional<Lease> activeLease(UUID workspaceId, String issueKey) {
        return jdbc.sql("select " + LEASE_COLUMNS + " from runner_lease where workspace_id = ? and issue_key = ? and released_at is null")
                .params(workspaceId, issueKey).query(JdbcRunnerStore::lease).optional();
    }

    @Override
    public boolean renew(UUID workspaceId, String issueKey, UUID machineId, Instant expiresAt) {
        return jdbc.sql("update runner_lease set expires_at = ? where workspace_id = ? and issue_key = ? and machine_id = ? and released_at is null")
                .params(Timestamp.from(expiresAt), workspaceId, issueKey, machineId).update() == 1;
    }

    @Override
    public Optional<Lease> release(UUID workspaceId, String issueKey, UUID machineId, String outcome, Instant now) {
        return jdbc.sql("update runner_lease set released_at = ?, outcome = ? where workspace_id = ? and issue_key = ? and machine_id = ? and released_at is null returning "
                        + LEASE_COLUMNS)
                .params(Timestamp.from(now), outcome, workspaceId, issueKey, machineId).query(JdbcRunnerStore::lease).optional();
    }

    @Override
    public List<Lease> expired(Instant now) {
        return jdbc.sql("select " + LEASE_COLUMNS + " from runner_lease where released_at is null and expires_at < ?")
                .param(Timestamp.from(now)).query(JdbcRunnerStore::lease).list();
    }

    @Override
    public int failures(UUID workspaceId, String issueKey, Instant since) {
        return jdbc.sql("select count(*) from runner_lease where workspace_id = ? and issue_key = ? and started_at > ? and outcome in ('EXPIRED', 'FAILED', 'TIMEOUT', 'STALLED')")
                .params(workspaceId, issueKey, Timestamp.from(since)).query(Integer.class).single();
    }

    @Override
    public Optional<UUID> lastEpicPerson(UUID workspaceId, String epicKey, Instant since) {
        return jdbc.sql("select member_id from runner_lease where workspace_id = ? and epic_key = ? and started_at > ? order by started_at desc limit 1")
                .params(workspaceId, epicKey, Timestamp.from(since)).query(UUID.class).optional();
    }

    @Override
    public void deleteLeasesBefore(Instant before) {
        jdbc.sql("delete from runner_lease where released_at is not null and released_at < ?").param(Timestamp.from(before)).update();
    }

    private static Lease lease(ResultSet rs, int n) throws SQLException {
        Timestamp released = rs.getTimestamp(11);
        return new Lease(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getObject(8, UUID.class), rs.getTimestamp(9).toInstant(),
                rs.getTimestamp(10).toInstant(), released == null ? null : released.toInstant(), rs.getString(12));
    }
}
