package ec.kuador.board.routine.application;

import ec.kuador.board.auth.AuthenticatedMember;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Las rutinas de cada tablero: a su hora (revisado cada minuto) o con su webhook crean la tarjeta y la asignan. El título
 * admite {fecha} («18 nov»). Si la tarjeta anterior sigue abierta y la rutina lo pide, no se crea otra: así no se apilan.
 */
@Service
public class RoutineService {

    private static final Logger log = LoggerFactory.getLogger(RoutineService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("es"));

    public record Input(String title, String description, String status, UUID assigneeId, String frequency, String weekdays, Integer monthDay,
            String at, String timezone, Boolean enabled, Boolean skipIfOpen) {}

    public record View(UUID id, String projectKey, String title, String description, String status, UUID assigneeId, String assigneeName,
            String frequency, String weekdays, Integer monthDay, String at, String timezone, String schedule, boolean enabled, boolean skipIfOpen,
            Instant nextRunAt, Instant lastRunAt, String lastIssueKey, boolean webhook) {}

    /** Resultado de correrla: la tarjeta creada, o por qué no (la anterior sigue abierta). */
    public record Run(String issueKey, String skipped) {}

    private record Row(UUID id, UUID workspaceId, String projectKey, String title, String description, String status, UUID assigneeId,
            String frequency, String weekdays, Integer monthDay, String at, String timezone, boolean enabled, boolean skipIfOpen, UUID createdBy,
            Instant nextRunAt, Instant lastRunAt, String lastIssueKey, boolean webhook) {

        Schedule schedule() {
            return Schedule.of(frequency, weekdays, monthDay, at, timezone);
        }
    }

    private final JdbcClient jdbc;
    private final ec.kuador.board.shared.cluster.ClusterLock lock;
    private final IssueApi issues;
    private final ProjectApi projects;
    private final MemberApi members;
    private final TransactionTemplate tx;
    private final Clock clock;

    RoutineService(JdbcClient jdbc, IssueApi issues, ProjectApi projects, MemberApi members, TransactionTemplate tx, Clock clock, ec.kuador.board.shared.cluster.ClusterLock lock) {
        this.lock = lock;
        this.jdbc = jdbc;
        this.issues = issues;
        this.projects = projects;
        this.members = members;
        this.tx = tx;
        this.clock = clock;
    }

    // ---- Lo que hace una persona ----

    public List<View> list(String projectKey) {
        board(projectKey);
        return rows("where workspace_id = ? and project_key = ? order by created_at", TenantContext.require(), projectKey).stream().map(this::view).toList();
    }

    public View create(AuthenticatedMember who, String projectKey, Input in) {
        requirePerson(who);
        ProjectView board = board(projectKey);
        Schedule s = validate(board, in);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        boolean enabled = in.enabled() == null || in.enabled();
        jdbc.sql("""
                insert into routine (id, workspace_id, project_key, title, description, status, assignee_id, frequency, weekdays, month_day, at_time,
                                     timezone, enabled, skip_if_open, created_by, next_run_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, TenantContext.require(), board.key(), in.title().strip(), blank(in.description()), blank(in.status()), in.assigneeId(), s.frequency(),
                        s.weekdaysText(), s.monthDay(), s.at().toString(), s.zone().getId(), enabled, in.skipIfOpen() == null || in.skipIfOpen(), who.memberId(),
                        enabled ? Timestamp.from(s.next(now)) : null, Timestamp.from(now))
                .update();
        return view(find(id));
    }

    public View update(AuthenticatedMember who, UUID id, Input in) {
        requirePerson(who);
        Row r = find(id);
        ProjectView board = board(r.projectKey());
        Schedule s = validate(board, in);
        boolean enabled = in.enabled() == null || in.enabled();
        jdbc.sql("""
                update routine set title = ?, description = ?, status = ?, assignee_id = ?, frequency = ?, weekdays = ?, month_day = ?, at_time = ?,
                                   timezone = ?, enabled = ?, skip_if_open = ?, next_run_at = ?
                where id = ? and workspace_id = ?""")
                .params(in.title().strip(), blank(in.description()), blank(in.status()), in.assigneeId(), s.frequency(), s.weekdaysText(), s.monthDay(),
                        s.at().toString(), s.zone().getId(), enabled, in.skipIfOpen() == null || in.skipIfOpen(),
                        enabled ? Timestamp.from(s.next(clock.instant())) : null, id, TenantContext.require())
                .update();
        return view(find(id));
    }

    public void delete(AuthenticatedMember who, UUID id) {
        requirePerson(who);
        Row r = find(id);
        board(r.projectKey());
        jdbc.sql("delete from routine where id = ? and workspace_id = ?").params(id, TenantContext.require()).update();
    }

    /** «Ejecutar ahora»: crea la tarjeta ya, sin mover su próxima fecha. */
    public Run runNow(AuthenticatedMember who, UUID id) {
        requirePerson(who);
        Row r = find(id);
        board(r.projectKey());
        return fire(r, false, null);
    }

    /** Genera (o rota) el webhook: el secreto se muestra una sola vez. */
    public String rotateWebhook(AuthenticatedMember who, UUID id) {
        requirePerson(who);
        Row r = find(id);
        board(r.projectKey());
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String secret = "kfh_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.sql("update routine set webhook_hash = ? where id = ? and workspace_id = ?").params(hash(secret), id, TenantContext.require()).update();
        return secret;
    }

    // ---- Desde afuera y a su hora ----

    /** El webhook (público, con su secreto): crea la tarjeta con el texto que llegue agregado a la descripción. */
    public Optional<Run> fromWebhook(UUID id, String secret, String text) {
        if (secret == null || secret.isBlank()) return Optional.empty();
        Optional<Row> found = TenantContext.asRoot(() -> rows("where id = ? and webhook_hash = ?", id, hash(secret.strip())).stream().findFirst());
        if (found.isEmpty()) return Optional.empty();
        Row r = found.get();
        return Optional.of(TenantContext.call(r.workspaceId(), () -> fire(r, false, text)));
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    void tickOnOneReplica() {
        lock.runExclusive("rutinas", java.time.Duration.ofMinutes(10), this::tick);
    }

    /** Cada minuto: las rutinas cuya hora llegó, de todos los espacios. */
    public void tick() {
        Instant now = clock.instant();
        List<Row> due = TenantContext.asRoot(() -> rows("where enabled and next_run_at <= ? order by next_run_at limit 50", Timestamp.from(now)));
        for (Row r : due) {
            try {
                TenantContext.run(r.workspaceId(), () -> fire(r, true, null));
            } catch (RuntimeException e) {
                // No se reintenta en bucle: se pasa a la próxima fecha y queda en el registro.
                log.warn("La rutina {} de {} no pudo correr: {}", r.id(), r.projectKey(), e.getMessage());
                TenantContext.run(r.workspaceId(), () -> advance(r, now));
            }
        }
    }

    private Run fire(Row r, boolean scheduled, String extra) {
        Instant now = clock.instant();
        return tx.execute(s -> {
            if (r.skipIfOpen() && r.lastIssueKey() != null && !issues.isClosed(r.lastIssueKey())) {
                if (scheduled) advance(r, now);
                return new Run(null, r.lastIssueKey() + " sigue abierta: no se crea otra hasta que se cierre.");
            }
            ZoneId zone = r.schedule().zone();
            String title = r.title().replace("{fecha}", DAY.format(now.atZone(zone)));
            if (title.length() > 255) title = title.substring(0, 255);
            String description = r.description();
            if (extra != null && !extra.isBlank()) {
                String clip = extra.strip().length() > 4000 ? extra.strip().substring(0, 4000) : extra.strip();
                description = (description == null ? "" : description + "\n\n") + "**Llegó por el webhook:**\n\n" + clip;
            }
            // La crea quien creó la rutina: si la asigna a un agente, esa persona la supervisa (su runner la atiende).
            UUID actor = r.createdBy() != null && members.find(r.createdBy()).isPresent() ? r.createdBy() : null;
            UUID assignee = r.assigneeId() != null && members.find(r.assigneeId()).isPresent() ? r.assigneeId() : null;
            IssueApi.IssueRef card = issues.createForRoutine(r.projectKey(), title, description, r.status(), assignee, actor);
            jdbc.sql("update routine set last_run_at = ?, last_issue_key = ?" + (scheduled ? ", next_run_at = ?" : "") + " where id = ?")
                    .params(scheduled ? new Object[] {Timestamp.from(now), card.key(), Timestamp.from(r.schedule().next(now)), r.id()}
                            : new Object[] {Timestamp.from(now), card.key(), r.id()})
                    .update();
            return new Run(card.key(), null);
        });
    }

    private void advance(Row r, Instant now) {
        jdbc.sql("update routine set next_run_at = ? where id = ?").params(Timestamp.from(r.schedule().next(now)), r.id()).update();
    }

    // ---- Apoyo ----

    private Schedule validate(ProjectView board, Input in) {
        if (in.title() == null || in.title().isBlank() || in.title().length() > 200) {
            throw new DomainException("Ponle un título a la rutina (máximo 200 caracteres); puede llevar {fecha}.");
        }
        if (in.status() != null && !in.status().isBlank() && board.column(in.status().strip()).isEmpty()) {
            throw new DomainException("La columna " + in.status() + " no existe en el tablero.");
        }
        if (in.assigneeId() != null && members.find(in.assigneeId()).isEmpty()) throw new DomainException("La persona o el agente elegido no existe.");
        return Schedule.of(in.frequency(), in.weekdays(), in.monthDay(), in.at(), in.timezone());
    }

    private ProjectView board(String projectKey) {
        String key = projectKey.toUpperCase(Locale.ROOT);
        if (!BoardScope.allows(key)) throw new NotFoundException("No existe el proyecto " + key + ".");
        return projects.find(key).orElseThrow(() -> new NotFoundException("No existe el proyecto " + key + "."));
    }

    private Row find(UUID id) {
        return rows("where id = ? and workspace_id = ?", id, TenantContext.require()).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("No existe la rutina " + id + "."));
    }

    private List<Row> rows(String where, Object... params) {
        return jdbc.sql("""
                select id, workspace_id, project_key, title, description, status, assignee_id, frequency, weekdays, month_day, at_time, timezone, enabled,
                       skip_if_open, created_by, next_run_at, last_run_at, last_issue_key, webhook_hash is not null
                from routine
                """ + where)
                .params(params)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9), (Integer) rs.getObject(10), rs.getString(11),
                        rs.getString(12), rs.getBoolean(13), rs.getBoolean(14), rs.getObject(15, UUID.class), instant(rs.getTimestamp(16)),
                        instant(rs.getTimestamp(17)), rs.getString(18), rs.getBoolean(19)))
                .list();
    }

    private View view(Row r) {
        String assignee = r.assigneeId() == null ? null : members.find(r.assigneeId()).map(MemberView::name).orElse(null);
        return new View(r.id(), r.projectKey(), r.title(), r.description(), r.status(), r.assigneeId(), assignee, r.frequency(), r.weekdays(), r.monthDay(),
                r.at(), r.timezone(), r.schedule().describe(), r.enabled(), r.skipIfOpen(), r.nextRunAt(), r.lastRunAt(), r.lastIssueKey(), r.webhook());
    }

    private static void requirePerson(AuthenticatedMember who) {
        if (who.agent() || who.runner()) throw new ForbiddenException("Las rutinas las configura una persona.");
        if (who.viewOnly()) throw new ForbiddenException("Con acceso de solo lectura no se configuran rutinas.");
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
