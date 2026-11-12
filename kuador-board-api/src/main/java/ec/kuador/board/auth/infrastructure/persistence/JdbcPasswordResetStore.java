package ec.kuador.board.auth.infrastructure.persistence;

import ec.kuador.board.auth.domain.PasswordReset;
import ec.kuador.board.auth.domain.PasswordResetStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPasswordResetStore implements PasswordResetStore {

    private static final String COLUMNS = "id, member_id, email, token_hash, code_hash, attempts, expires_at, used_at, created_at";

    private final JdbcClient jdbc;

    JdbcPasswordResetStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(PasswordReset r) {
        jdbc.sql("insert into password_reset (" + COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(r.id(), r.memberId(), r.email(), r.tokenHash(), r.codeHash(), r.attempts(), Timestamp.from(r.expiresAt()),
                        r.usedAt() == null ? null : Timestamp.from(r.usedAt()), Timestamp.from(r.createdAt()))
                .update();
    }

    @Override
    public Optional<PasswordReset> findByTokenHash(String tokenHash) {
        return jdbc.sql("select " + COLUMNS + " from password_reset where token_hash = ?").param(tokenHash).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<PasswordReset> findLatest(String email) {
        return jdbc.sql("select " + COLUMNS + " from password_reset where email = ? order by created_at desc limit 1").param(email)
                .query((rs, n) -> map(rs)).optional();
    }

    @Override
    public int countSince(String email, Instant since) {
        return jdbc.sql("select count(*) from password_reset where email = ? and created_at > ?").params(email, Timestamp.from(since))
                .query(Integer.class).single();
    }

    @Override
    public int countAllSince(Instant since) {
        return jdbc.sql("select count(*) from password_reset where created_at > ?").param(Timestamp.from(since)).query(Integer.class).single();
    }

    @Override
    public void failedAttempt(UUID id) {
        jdbc.sql("update password_reset set attempts = attempts + 1 where id = ?").param(id).update();
    }

    @Override
    public void markUsed(UUID id, Instant now) {
        jdbc.sql("update password_reset set used_at = ? where id = ?").params(Timestamp.from(now), id).update();
    }

    private static PasswordReset map(ResultSet rs) throws SQLException {
        Timestamp used = rs.getTimestamp("used_at");
        return new PasswordReset(rs.getObject("id", UUID.class), rs.getObject("member_id", UUID.class), rs.getString("email"),
                rs.getString("token_hash"), rs.getString("code_hash"), rs.getInt("attempts"), rs.getTimestamp("expires_at").toInstant(),
                used == null ? null : used.toInstant(), rs.getTimestamp("created_at").toInstant());
    }
}
