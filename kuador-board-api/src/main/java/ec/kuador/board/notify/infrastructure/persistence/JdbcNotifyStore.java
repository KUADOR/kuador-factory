package ec.kuador.board.notify.infrastructure.persistence;

import ec.kuador.board.notify.domain.Channel;
import ec.kuador.board.notify.domain.Notice;
import ec.kuador.board.notify.domain.NotificationSettings;
import ec.kuador.board.notify.domain.NotifyStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL directo: la cola se procesa para todos los espacios a la vez, sin el filtro por espacio de las entidades. */
@Repository
class JdbcNotifyStore implements NotifyStore {

    private final JdbcClient jdbc;

    JdbcNotifyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<NotificationSettings> settings(UUID memberId) {
        return jdbc.sql("""
                select member_id, email, telegram, telegram_chat_id, on_assigned, on_gates, on_plans
                from notification_settings where member_id = ?""")
                .param(memberId)
                .query((rs, n) -> new NotificationSettings(rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getBoolean(3),
                        (Long) rs.getObject(4), rs.getBoolean(5), rs.getBoolean(6), rs.getBoolean(7)))
                .optional();
    }

    @Override
    public void saveSettings(UUID workspaceId, NotificationSettings s, Instant now) {
        jdbc.sql("""
                insert into notification_settings (member_id, workspace_id, email, telegram, telegram_chat_id, on_assigned, on_gates, on_plans, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (member_id) do update set email = excluded.email, telegram = excluded.telegram,
                    telegram_chat_id = excluded.telegram_chat_id, on_assigned = excluded.on_assigned, on_gates = excluded.on_gates,
                    on_plans = excluded.on_plans, updated_at = excluded.updated_at""")
                .params(s.memberId(), workspaceId, s.email(), s.telegram(), s.telegramChatId(), s.onAssigned(), s.onGates(), s.onPlans(), Timestamp.from(now))
                .update();
    }

    @Override
    public void saveLinkCode(String codeHash, UUID workspaceId, UUID memberId, Instant expiresAt) {
        jdbc.sql("delete from telegram_link where member_id = ?").param(memberId).update();
        jdbc.sql("insert into telegram_link (code_hash, workspace_id, member_id, expires_at) values (?, ?, ?, ?)")
                .params(codeHash, workspaceId, memberId, Timestamp.from(expiresAt)).update();
    }

    @Override
    public Optional<LinkTarget> consumeLinkCode(String codeHash, Instant now) {
        return jdbc.sql("delete from telegram_link where code_hash = ? and expires_at > ? returning workspace_id, member_id")
                .params(codeHash, Timestamp.from(now))
                .query((rs, n) -> new LinkTarget(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .optional();
    }

    @Override
    public void enqueue(Notice n, Instant now) {
        jdbc.sql("""
                insert into notification (id, workspace_id, member_id, channel, address, subject, body, link, status, attempts, next_attempt_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)""")
                .params(n.id(), n.workspaceId(), n.memberId(), n.channel().name(), n.address(), n.subject(), n.body(), n.link(), Timestamp.from(now),
                        Timestamp.from(now))
                .update();
    }

    @Override
    public List<Notice> lockDue(Instant now, int limit) {
        return jdbc.sql("""
                select id, workspace_id, member_id, channel, address, subject, body, link, attempts, created_at from notification
                where status = 'PENDING' and next_attempt_at <= ? order by created_at limit ? for update skip locked""")
                .params(Timestamp.from(now), limit)
                .query((rs, n) -> new Notice(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        Channel.Kind.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9),
                        rs.getTimestamp(10).toInstant()))
                .list();
    }

    @Override
    public void markSent(UUID id, Instant now) {
        jdbc.sql("update notification set status = 'SENT', sent_at = ?, error = null where id = ?").params(Timestamp.from(now), id).update();
    }

    @Override
    public void markRetry(UUID id, int attempts, Instant nextAttempt, String error) {
        jdbc.sql("update notification set attempts = ?, next_attempt_at = ?, error = ? where id = ?")
                .params(attempts, Timestamp.from(nextAttempt), truncate(error), id).update();
    }

    @Override
    public void markFailed(UUID id, int attempts, String error) {
        jdbc.sql("update notification set status = 'FAILED', attempts = ?, error = ? where id = ?").params(attempts, truncate(error), id).update();
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
    }
}
