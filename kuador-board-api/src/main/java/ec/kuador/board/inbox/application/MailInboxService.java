package ec.kuador.board.inbox.application;

import ec.kuador.board.attachment.AttachmentApi;
import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.connections.ConnectionsApi;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.ForbiddenException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.BoardScope;
import ec.kuador.board.shared.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La bandeja de correo de cada tablero. Cada minuto revisa los buzones de las bandejas activas y crea una tarjeta por
 * cada correo nuevo, con su texto y sus adjuntos. No marca nada en el buzón (eso queda para el agente, si se le
 * permite): recuerda lo que ya vio por el Message-ID de cada correo.
 */
@Service
public class MailInboxService implements ec.kuador.board.inbox.InboxApi {

    private static final Logger log = LoggerFactory.getLogger(MailInboxService.class);
    /** Correos que se convierten en tarjetas por bandeja y por vuelta: el resto sigue en la próxima. */
    private static final int BATCH = 10;
    /**
     * Correos que se le piden al buzón por vuelta: más que BATCH, porque los primeros pueden ser ya vistos (se vuelve a
     * mirar un rato antes de lo último); si no, una ráfaga de correos dejaría la bandeja trabada releyendo los mismos.
     */
    private static final int FETCH = 50;
    /** Se vuelve a mirar un poco antes de lo último visto: los relojes de los servidores no coinciden al segundo. */
    private static final Duration OVERLAP = Duration.ofMinutes(5);
    private static final int MAX_BACKFILL_DAYS = 7;
    /** Tarjetas por bandeja en una hora: más que esto huele a bucle (un aviso que vuelve al buzón) y la bandeja se pausa. */
    static final int MAX_PER_HOUR = 30;

    public record Input(String connection, String folder, String fromFilter, Boolean onlyAttachments, String status, UUID assigneeId, Boolean enabled,
            Integer backfillDays) {}

    public record View(UUID id, String projectKey, String connection, String connectionName, String folder, String fromFilter, boolean onlyAttachments,
            String status, UUID assigneeId, String assigneeName, boolean enabled, Instant since, Instant lastCheckedAt, String lastError, String lastIssueKey,
            int cardsCreated) {}

    /** Resultado de revisar: las tarjetas creadas o el error que devolvió el buzón. */
    public record Check(List<String> created, String error) {}

    private record Row(UUID id, UUID workspaceId, String projectKey, String connection, String folder, String fromFilter, boolean onlyAttachments,
            String status, UUID assigneeId, boolean enabled, UUID createdBy, Instant since, Instant lastCheckedAt, String lastError, String lastIssueKey,
            int cardsCreated) {}

    private final JdbcClient jdbc;
    private final ec.kuador.board.shared.cluster.ClusterLock lock;
    private final ConnectionsApi connections;
    private final IssueApi issues;
    private final AttachmentApi attachments;
    private final ProjectApi projects;
    private final MemberApi members;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ZoneId zone;
    /** La dirección desde la que el tablero manda sus avisos: lo que llegue de ahí no es trabajo. */
    private final String platformSender;

    MailInboxService(JdbcClient jdbc, ConnectionsApi connections, IssueApi issues, AttachmentApi attachments, ProjectApi projects, MemberApi members,
            TransactionTemplate tx, Clock clock, @Value("${board.inbox.timezone:America/Guayaquil}") String zone,
            @Value("${board.mail.from:Kuador Go <no-reply@kuador.com>}") String mailFrom, ec.kuador.board.shared.cluster.ClusterLock lock) {
        this.lock = lock;
        this.jdbc = jdbc;
        this.connections = connections;
        this.issues = issues;
        this.attachments = attachments;
        this.projects = projects;
        this.members = members;
        this.tx = tx;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[^\\s<>@,;\"]+@[^\\s<>@,;\"]+").matcher(mailFrom == null ? "" : mailFrom);
        this.platformSender = m.find() ? m.group().toLowerCase(Locale.ROOT) : null;
    }

    // ---- Lo que hace una persona ----

    public List<ConnectionsApi.Mailbox> mailboxes() {
        return connections.mailboxes();
    }

    public List<View> list(String projectKey) {
        ProjectView board = board(projectKey);
        return rows("where workspace_id = ? and project_key = ? order by created_at", TenantContext.require(), board.key()).stream().map(this::view).toList();
    }

    public View create(AuthenticatedMember who, String projectKey, Input in) {
        requirePerson(who);
        ProjectView board = board(projectKey);
        String connection = validate(board, in);
        int days = in.backfillDays() == null ? 0 : in.backfillDays();
        if (days < 0 || days > MAX_BACKFILL_DAYS) throw new DomainException("Se pueden traer los correos de hasta " + MAX_BACKFILL_DAYS + " días atrás.");
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        return tx.execute(s -> {
            jdbc.sql("""
                    insert into mail_inbox (id, workspace_id, project_key, conn_key, folder, from_filter, only_attachments, status, assignee_id, enabled,
                                            created_by, since, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, TenantContext.require(), board.key(), connection, blank(in.folder()), blank(in.fromFilter()), Boolean.TRUE.equals(in.onlyAttachments()),
                            blank(in.status()), in.assigneeId(), in.enabled() == null || in.enabled(), who.memberId(), Timestamp.from(now.minus(Duration.ofDays(days))),
                            Timestamp.from(now))
                    .update();
            // El agente que trabaja la tarjeta usa el mismo buzón para leer el correo o responderlo.
            connections.useOnBoard(connection, board.key());
            return view(find(id));
        });
    }

    public View update(AuthenticatedMember who, UUID id, Input in) {
        requirePerson(who);
        Row r = find(id);
        ProjectView board = board(r.projectKey());
        String connection = validate(board, in);
        jdbc.sql("""
                update mail_inbox set conn_key = ?, folder = ?, from_filter = ?, only_attachments = ?, status = ?, assignee_id = ?, enabled = ?
                where id = ? and workspace_id = ?""")
                .params(connection, blank(in.folder()), blank(in.fromFilter()), Boolean.TRUE.equals(in.onlyAttachments()), blank(in.status()), in.assigneeId(),
                        in.enabled() == null || in.enabled(), id, TenantContext.require())
                .update();
        if (!connection.equals(r.connection())) connections.useOnBoard(connection, board.key());
        return view(find(id));
    }

    public void delete(AuthenticatedMember who, UUID id) {
        requirePerson(who);
        Row r = find(id);
        board(r.projectKey());
        jdbc.sql("delete from mail_inbox where id = ? and workspace_id = ?").params(id, TenantContext.require()).update();
    }

    /** «Revisar ahora»: no espera al próximo minuto. */
    public Check checkNow(AuthenticatedMember who, UUID id) {
        requirePerson(who);
        Row r = find(id);
        board(r.projectKey());
        return check(r);
    }

    @Override
    public void receive(String projectKey, String connection, UUID createdBy) {
        String board = projectKey.toUpperCase(Locale.ROOT);
        String conn = connection.strip().toUpperCase(Locale.ROOT);
        UUID ws = TenantContext.require();
        boolean exists = jdbc.sql("select count(*) from mail_inbox where workspace_id = ? and project_key = ? and conn_key = ?").params(ws, board, conn)
                .query(Long.class).single() > 0;
        if (exists || connections.mailboxes().stream().noneMatch(m -> m.key().equals(conn))) return;
        Instant now = clock.instant();
        jdbc.sql("insert into mail_inbox (id, workspace_id, project_key, conn_key, only_attachments, enabled, created_by, since, created_at) "
                + "values (?, ?, ?, ?, false, true, ?, ?, ?)")
                .params(UUID.randomUUID(), ws, board, conn, createdBy, Timestamp.from(now), Timestamp.from(now)).update();
        connections.useOnBoard(conn, board);
    }

    // ---- Cada minuto ----

    @Scheduled(fixedDelayString = "${board.inbox.check-every:60000}", initialDelay = 60_000)
    void tickOnOneReplica() {
        lock.runExclusive("correo-entrante", java.time.Duration.ofMinutes(15), this::tick);
    }

    public void tick() {
        List<Row> active = TenantContext.asRoot(() -> rows("where enabled order by last_checked_at nulls first"));
        for (Row r : active) {
            try {
                TenantContext.run(r.workspaceId(), () -> check(r));
            } catch (RuntimeException e) {
                log.warn("La bandeja {} de {} no se pudo revisar: {}", r.id(), r.projectKey(), e.getMessage());
            }
        }
    }

    private Check check(Row r) {
        Instant now = clock.instant();
        List<ConnectionsApi.IncomingMail> mail;
        try {
            Instant from = r.since().minus(OVERLAP);
            // Lo ya procesado en el último día se pide sin texto ni adjuntos: no se descarga cada minuto otra vez.
            java.util.Set<String> known = new java.util.HashSet<>();
            jdbc.sql("select mail_key from mail_inbox_seen where inbox_id = ? and seen_at > ?").params(r.id(), Timestamp.from(now.minus(Duration.ofDays(1))))
                    .query(String.class).list().forEach(k -> known.add(k.substring(k.indexOf(':') + 1)));
            mail = connections.recentMail(r.connection(), r.folder(), from, FETCH, known);
        } catch (RuntimeException e) {
            String error = e.getMessage() == null ? "No se pudo leer el buzón." : e.getMessage();
            jdbc.sql("update mail_inbox set last_checked_at = ?, last_error = ? where id = ?").params(Timestamp.from(now), error, r.id()).update();
            return new Check(List.of(), error);
        }
        // Freno de bucles: si ya entraron demasiadas tarjetas en una hora, la bandeja se pausa y se avisa por qué.
        long lastHour = jdbc.sql("select count(*) from mail_inbox_seen where inbox_id = ? and issue_key is not null and seen_at > ?")
                .params(r.id(), Timestamp.from(now.minus(Duration.ofHours(1)))).query(Long.class).single();
        if (lastHour >= MAX_PER_HOUR) {
            String error = pause(r, now);
            return new Check(List.of(), error);
        }
        String mailbox = connections.mailboxes().stream().filter(m -> m.key().equals(r.connection())).map(ConnectionsApi.Mailbox::name).findFirst()
                .orElse(r.connection());
        List<String> created = new ArrayList<>();
        Instant since = r.since();
        String error = null;
        for (ConnectionsApi.IncomingMail m : mail) {
            String key = key(m);
            boolean already = seen(r.id(), key);
            // Ya se crearon las de esta vuelta: este correo y los que siguen quedan para la próxima.
            if (!already && created.size() >= BATCH) break;
            // Pausada por exceso: este correo y los que siguen quedan para cuando se reactive.
            if (!already && lastHour + created.size() >= MAX_PER_HOUR) {
                error = pause(r, now);
                break;
            }
            if (m.receivedAt().isAfter(since)) since = m.receivedAt();
            if (already) continue;
            if (!matches(r, m) || m.automatic() || fromPlatform(m)) {
                markSeen(r.id(), key, null, now);
                continue;
            }
            try {
                created.add(tx.execute(s -> card(r, m, key, mailbox, now)));
            } catch (RuntimeException e) {
                // Se reintenta en la próxima vuelta: no se marca como visto ni se avanza más allá de este correo.
                error = "No se pudo crear la tarjeta de «" + MailCard.title(m) + "»: " + e.getMessage();
                log.warn("Bandeja {}: {}", r.id(), error);
                since = m.receivedAt().minusSeconds(1).isAfter(r.since()) ? m.receivedAt().minusSeconds(1) : r.since();
                break;
            }
        }
        jdbc.sql("""
                update mail_inbox set since = ?, last_checked_at = ?, last_error = ?, cards_created = cards_created + ?,
                                      last_issue_key = coalesce(?, last_issue_key)
                where id = ?""")
                .params(Timestamp.from(since), Timestamp.from(now), error, created.size(), created.isEmpty() ? null : created.getLast(), r.id())
                .update();
        return new Check(created, error);
    }

    /** Crea la tarjeta con sus adjuntos, en una sola transacción: el agente no la ve hasta que está completa. */
    private String card(Row r, ConnectionsApi.IncomingMail m, String key, String mailbox, Instant now) {
        UUID actor = r.createdBy() != null && members.find(r.createdBy()).isPresent() ? r.createdBy() : null;
        UUID assignee = r.assigneeId() != null && members.find(r.assigneeId()).isPresent() ? r.assigneeId() : null;
        List<String> attached = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<ConnectionsApi.MailFile> toAttach = new ArrayList<>();
        for (ConnectionsApi.MailFile f : m.files()) {
            if (f.content() == null) skipped.add(f.name() + " (pesa demasiado)");
            else if (!attachments.available()) skipped.add(f.name() + " (el tablero no tiene almacenamiento de adjuntos)");
            else toAttach.add(f);
        }
        toAttach.forEach(f -> attached.add(f.name()));
        IssueApi.IssueRef issue = issues.createFromMail(r.projectKey(), MailCard.title(m), MailCard.description(m, mailbox, zone, attached, skipped), r.status(),
                assignee, actor);
        for (ConnectionsApi.MailFile f : toAttach) {
            attachments.attach(issue.key(), f.name(), f.contentType(), f.content(), actor);
        }
        markSeen(r.id(), key, issue.key(), now);
        return issue.key();
    }

    private String pause(Row r, Instant now) {
        String error = "Se pausó: entraron " + MAX_PER_HOUR + " correos en una hora (¿un bucle?). Revisa los filtros y vuelve a activarla.";
        jdbc.sql("update mail_inbox set enabled = false, last_error = ?, last_checked_at = ? where id = ?").params(error, Timestamp.from(now), r.id()).update();
        log.warn("Bandeja {} de {} pausada por exceso de correos", r.id(), r.projectKey());
        return error;
    }

    /** Los avisos del propio tablero (si el buzón conectado es el mismo que los envía) no son trabajo. */
    private boolean fromPlatform(ConnectionsApi.IncomingMail m) {
        return platformSender != null && m.from() != null && m.from().toLowerCase(Locale.ROOT).contains(platformSender);
    }

    private static boolean matches(Row r, ConnectionsApi.IncomingMail m) {
        if (r.onlyAttachments() && m.files().isEmpty()) return false;
        if (r.fromFilter() == null) return true;
        String from = m.from() == null ? "" : m.from().toLowerCase(Locale.ROOT);
        for (String wanted : r.fromFilter().toLowerCase(Locale.ROOT).split("[,;\\s]+")) {
            if (!wanted.isBlank() && from.contains(wanted.strip())) return true;
        }
        return false;
    }

    private static String key(ConnectionsApi.IncomingMail m) {
        String k = m.messageId() != null && !m.messageId().isBlank() ? "mid:" + m.messageId().strip() : "id:" + m.id();
        return k.length() > 500 ? k.substring(0, 500) : k;
    }

    private boolean seen(UUID inbox, String key) {
        return jdbc.sql("select count(*) from mail_inbox_seen where inbox_id = ? and mail_key = ?").params(inbox, key).query(Long.class).single() > 0;
    }

    private void markSeen(UUID inbox, String key, String issueKey, Instant now) {
        jdbc.sql("insert into mail_inbox_seen (inbox_id, mail_key, issue_key, seen_at) values (?, ?, ?, ?) on conflict do nothing")
                .params(inbox, key, issueKey, Timestamp.from(now)).update();
    }

    // ---- Apoyo ----

    private String validate(ProjectView board, Input in) {
        if (in.connection() == null || in.connection().isBlank()) throw new DomainException("Elige el buzón de correo.");
        String connection = in.connection().strip().toUpperCase(Locale.ROOT);
        if (connections.mailboxes().stream().noneMatch(m -> m.key().equals(connection))) {
            throw new DomainException("«" + connection + "» no es un buzón conectado (o falta iniciar sesión): conéctalo en Conexiones.");
        }
        if (in.folder() != null && !in.folder().isBlank() && !in.folder().strip().matches("[\\p{L}\\p{N} ._/\\-]{1,100}")) {
            throw new DomainException("El nombre de la carpeta no es válido.");
        }
        if (in.fromFilter() != null && in.fromFilter().length() > 200) throw new DomainException("El filtro de remitentes tiene hasta 200 caracteres.");
        if (in.status() != null && !in.status().isBlank() && board.column(in.status().strip()).isEmpty()) {
            throw new DomainException("El paso " + in.status() + " no existe en el tablero.");
        }
        if (in.assigneeId() != null && members.find(in.assigneeId()).isEmpty()) throw new DomainException("La persona o el agente elegido no existe.");
        return connection;
    }

    private ProjectView board(String projectKey) {
        String key = projectKey.toUpperCase(Locale.ROOT);
        if (!BoardScope.allows(key)) throw new NotFoundException("No existe el tablero " + key + ".");
        return projects.find(key).orElseThrow(() -> new NotFoundException("No existe el tablero " + key + "."));
    }

    private Row find(UUID id) {
        return rows("where id = ? and workspace_id = ?", id, TenantContext.require()).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No existe esa bandeja de correo."));
    }

    private List<Row> rows(String where, Object... params) {
        return jdbc.sql("""
                select id, workspace_id, project_key, conn_key, folder, from_filter, only_attachments, status, assignee_id, enabled, created_by, since,
                       last_checked_at, last_error, last_issue_key, cards_created
                from mail_inbox
                """ + where)
                .params(params)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getBoolean(7), rs.getString(8), rs.getObject(9, UUID.class), rs.getBoolean(10), rs.getObject(11, UUID.class),
                        rs.getTimestamp(12).toInstant(), instant(rs.getTimestamp(13)), rs.getString(14), rs.getString(15), rs.getInt(16)))
                .list();
    }

    private View view(Row r) {
        String assignee = r.assigneeId() == null ? null : members.find(r.assigneeId()).map(MemberView::name).orElse(null);
        String name = connections.mailboxes().stream().filter(m -> m.key().equals(r.connection())).map(ConnectionsApi.Mailbox::name).findFirst()
                .orElse(r.connection());
        return new View(r.id(), r.projectKey(), r.connection(), name, r.folder(), r.fromFilter(), r.onlyAttachments(), r.status(), r.assigneeId(), assignee,
                r.enabled(), r.since(), r.lastCheckedAt(), r.lastError(), r.lastIssueKey(), r.cardsCreated());
    }

    private static void requirePerson(AuthenticatedMember who) {
        if (who.agent() || who.runner()) throw new ForbiddenException("La bandeja de correo la configura una persona.");
        if (who.viewOnly()) throw new ForbiddenException("Con acceso de solo lectura no se configura la bandeja de correo.");
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
