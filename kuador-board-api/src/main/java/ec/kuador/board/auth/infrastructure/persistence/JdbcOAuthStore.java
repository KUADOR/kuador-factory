package ec.kuador.board.auth.infrastructure.persistence;

import ec.kuador.board.auth.domain.AuthorizationCode;
import ec.kuador.board.auth.domain.OAuthClient;
import ec.kuador.board.auth.domain.OAuthStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Tablas globales (sin espacio): SQL directo, sin el filtro por espacio de las entidades. */
@Repository
class JdbcOAuthStore implements OAuthStore {

    private final JdbcClient jdbc;

    JdbcOAuthStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveClient(OAuthClient c) {
        jdbc.sql("insert into oauth_client (client_id, name, redirect_uris, created_at) values (?, ?, ?, ?)")
                .params(c.clientId(), c.name(), String.join("\n", c.redirectUris()), Timestamp.from(c.createdAt()))
                .update();
    }

    @Override
    public Optional<OAuthClient> findClient(String clientId) {
        return jdbc.sql("select client_id, name, redirect_uris, created_at from oauth_client where client_id = ?")
                .param(clientId)
                .query((rs, n) -> new OAuthClient(rs.getString(1), rs.getString(2), Arrays.asList(rs.getString(3).split("\n")),
                        rs.getTimestamp(4).toInstant()))
                .optional();
    }

    @Override
    public void saveCode(AuthorizationCode c) {
        jdbc.sql("""
                insert into oauth_code (code_hash, client_id, workspace_id, member_id, redirect_uri, code_challenge, expires_at)
                values (?, ?, ?, ?, ?, ?, ?)""")
                .params(c.codeHash(), c.clientId(), c.workspaceId(), c.memberId(), c.redirectUri(), c.codeChallenge(), Timestamp.from(c.expiresAt()))
                .update();
    }

    @Override
    public Optional<AuthorizationCode> findCode(String codeHash) {
        return jdbc.sql("""
                select code_hash, client_id, workspace_id, member_id, redirect_uri, code_challenge, expires_at, used_at
                from oauth_code where code_hash = ?""")
                .param(codeHash)
                .query((rs, n) -> new AuthorizationCode(rs.getString(1), rs.getString(2), rs.getObject(3, java.util.UUID.class),
                        rs.getObject(4, java.util.UUID.class), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant(),
                        rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()))
                .optional();
    }

    @Override
    public boolean markUsed(String codeHash, Instant now) {
        return jdbc.sql("update oauth_code set used_at = ? where code_hash = ? and used_at is null")
                .params(Timestamp.from(now), codeHash).update() == 1;
    }

    @Override
    public int deleteExpiredCodes(Instant now) {
        return jdbc.sql("delete from oauth_code where expires_at < ?").param(Timestamp.from(now)).update();
    }
}
