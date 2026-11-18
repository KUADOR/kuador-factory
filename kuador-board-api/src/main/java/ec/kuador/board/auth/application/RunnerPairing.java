package ec.kuador.board.auth.application;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.auth.domain.Tokens;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.ForbiddenException;
import ec.kuador.board.shared.error.NotFoundException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emparejar un runner, como una TV: el runner pide un código, la persona lo aprueba en el tablero con su sesión y el
 * runner recibe su conexión de agentes locales una sola vez. Sirve igual por SSH en un Linux sin navegador. Solo se
 * guarda el hash del código del dispositivo; la conexión vence como las de inicio de sesión (90 días).
 */
@Service
public class RunnerPairing {

    static final Duration TTL = Duration.ofMinutes(10);
    /** Sin letras que se confunden (0/O, 1/I/L). */
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Started(String deviceCode, String userCode, String verifyUrl, int interval, long expiresIn) {}

    /** {@code token} nulo = todavía no lo aprueban. */
    public record Poll(String token) {}

    private final JdbcClient jdbc;
    private final AuthService auth;
    private final Clock clock;
    private final String webUrl;

    RunnerPairing(JdbcClient jdbc, AuthService auth, Clock clock, @Value("${board.web-url:http://localhost:3600}") String webUrl) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.clock = clock;
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    @Transactional
    public Started start(String machineName) {
        String name = machineName == null || machineName.isBlank() ? "Runner" : machineName.strip();
        if (name.length() > 60) name = name.substring(0, 60);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String device = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String code = userCode();
        Instant now = clock.instant();
        jdbc.sql("insert into runner_pairing (device_hash, user_code, machine_name, expires_at) values (?, ?, ?, ?)")
                .params(Tokens.hash(device), code, name, Timestamp.from(now.plus(TTL))).update();
        return new Started(device, code, webUrl + "/local-agents/pair?code=" + code, 3, TTL.toSeconds());
    }

    /** La persona aprueba el código con su sesión: la máquina queda conectada a su nombre. */
    @Transactional
    public String approve(AuthenticatedMember who, String userCode) {
        if (who.agent() || who.runner()) throw new ForbiddenException("Solo una persona aprueba una máquina.");
        if (who.viewOnly()) throw new ForbiddenException("Con acceso de solo lectura no se pueden poner agentes a trabajar.");
        String code = normalize(userCode);
        var row = jdbc.sql("select machine_name, member_id from runner_pairing where user_code = ? and expires_at > ?")
                .params(code, Timestamp.from(clock.instant()))
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getObject(2, UUID.class)}).optional()
                .orElseThrow(() -> new NotFoundException("El código " + code + " no existe o ya venció: pide uno nuevo en la máquina."));
        if (row[1] != null) throw new DomainException("Ese código ya se aprobó.");
        jdbc.sql("update runner_pairing set workspace_id = ?, member_id = ? where user_code = ? and member_id is null")
                .params(who.workspaceId(), who.memberId(), code).update();
        return (String) row[0];
    }

    /**
     * El runner pregunta si ya lo aprobaron; la conexión se entrega una sola vez. Sin transacción propia: la conexión se
     * emite ya dentro del espacio de quien aprobó (la petición llega sin sesión).
     */
    public Optional<Poll> poll(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) return Optional.empty();
        String hash = Tokens.hash(deviceCode.strip());
        var row = jdbc.sql("select machine_name, workspace_id, member_id, expires_at, consumed from runner_pairing where device_hash = ?")
                .param(hash)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getTimestamp(4).toInstant(),
                        rs.getBoolean(5)})
                .optional().orElse(null);
        if (row == null || (boolean) row[4] || ((Instant) row[3]).isBefore(clock.instant())) return Optional.empty();
        if (row[2] == null) return Optional.of(new Poll(null));
        if (jdbc.sql("update runner_pairing set consumed = true where device_hash = ? and consumed = false").param(hash).update() != 1) return Optional.empty();
        var issued = auth.issueRunnerToken((UUID) row[1], (UUID) row[2], "Runner · " + row[0]);
        return Optional.of(new Poll(issued.token()));
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    void cleanup() {
        jdbc.sql("delete from runner_pairing where expires_at < ?").param(Timestamp.from(clock.instant().minus(Duration.ofHours(1)))).update();
    }

    private static String userCode() {
        StringBuilder b = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) b.append('-');
            b.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return b.toString();
    }

    static String normalize(String code) {
        String c = code == null ? "" : code.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return c.length() == 8 ? c.substring(0, 4) + "-" + c.substring(4) : c;
    }
}
