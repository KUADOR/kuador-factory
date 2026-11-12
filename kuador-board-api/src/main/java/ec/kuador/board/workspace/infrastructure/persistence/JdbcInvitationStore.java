package ec.kuador.board.workspace.infrastructure.persistence;

import ec.kuador.board.workspace.domain.Invitation;
import ec.kuador.board.workspace.domain.InvitationStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcInvitationStore implements InvitationStore {

    private static final String COLUMNS = "id, workspace_id, member_id, email, token_hash, invited_by, expires_at, accepted_at, created_at";

    private final JdbcClient jdbc;

    JdbcInvitationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Invitation i) {
        jdbc.sql("insert into workspace_invitation (" + COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(i.id(), i.workspaceId(), i.memberId(), i.email(), i.tokenHash(), i.invitedBy(), Timestamp.from(i.expiresAt()),
                        i.acceptedAt() == null ? null : Timestamp.from(i.acceptedAt()), Timestamp.from(i.createdAt()))
                .update();
    }

    @Override
    public Optional<Invitation> find(UUID workspaceId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from workspace_invitation where workspace_id = ? and id = ?").params(workspaceId, id)
                .query((rs, n) -> map(rs)).optional();
    }

    @Override
    public Optional<Invitation> findByTokenHash(String tokenHash) {
        return jdbc.sql("select " + COLUMNS + " from workspace_invitation where token_hash = ?").param(tokenHash).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public List<Invitation> pending(UUID workspaceId) {
        return jdbc.sql("select " + COLUMNS + " from workspace_invitation where workspace_id = ? and accepted_at is null order by created_at desc")
                .param(workspaceId).query((rs, n) -> map(rs)).list();
    }

    @Override
    public void deletePending(UUID workspaceId, UUID memberId) {
        jdbc.sql("delete from workspace_invitation where workspace_id = ? and member_id = ? and accepted_at is null").params(workspaceId, memberId).update();
    }

    @Override
    public void markAccepted(UUID id, Instant now) {
        jdbc.sql("update workspace_invitation set accepted_at = ? where id = ?").params(Timestamp.from(now), id).update();
    }

    private static Invitation map(ResultSet rs) throws SQLException {
        Timestamp accepted = rs.getTimestamp("accepted_at");
        return new Invitation(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class), rs.getObject("member_id", UUID.class),
                rs.getString("email"), rs.getString("token_hash"), rs.getObject("invited_by", UUID.class), rs.getTimestamp("expires_at").toInstant(),
                accepted == null ? null : accepted.toInstant(), rs.getTimestamp("created_at").toInstant());
    }
}
