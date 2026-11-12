package ec.kuador.board.auth.application;

import ec.kuador.board.auth.domain.AccessTokenRepository;
import ec.kuador.board.auth.domain.Credential;
import ec.kuador.board.auth.domain.CredentialRepository;
import ec.kuador.board.auth.domain.PasswordHasher;
import ec.kuador.board.auth.domain.PasswordReset;
import ec.kuador.board.auth.domain.PasswordResetStore;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.mail.Mailer;
import ec.kuador.board.shared.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recuperar la contraseña sin sesión. El correo trae un enlace y un código de 6 dígitos; con cualquiera de
 * los dos se pone una contraseña nueva y se cierran todas las sesiones. La respuesta al pedirlo es la misma
 * exista o no la cuenta, para no revelar qué correos están registrados.
 *
 * <p>Límites: un correo por minuto y cinco por hora por dirección, un tope global por minuto, 30 minutos de
 * validez y cinco intentos de código.
 */
@Service
public class PasswordResetService {

    static final int PER_HOUR = 5;
    static final int GLOBAL_PER_MINUTE = 120;

    private final PasswordResetStore resets;
    private final CredentialRepository credentials;
    private final AccessTokenRepository tokens;
    private final PasswordHasher hasher;
    private final Mailer mailer;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String webUrl;
    private final SecureRandom random = new SecureRandom();

    PasswordResetService(PasswordResetStore resets, CredentialRepository credentials, AccessTokenRepository tokens, PasswordHasher hasher,
            Mailer mailer, PlatformTransactionManager txManager, Clock clock, @Value("${board.web-url:http://localhost:3600}") String webUrl) {
        this.resets = resets;
        this.credentials = credentials;
        this.tokens = tokens;
        this.hasher = hasher;
        this.mailer = mailer;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    /** Envía el enlace y el código si el correo tiene cuenta. Siempre responde igual. */
    public void request(String rawEmail) {
        String email = Credential.normalizeEmail(rawEmail);
        Instant now = clock.instant();
        String token = newToken();
        String code = "%06d".formatted(random.nextInt(1_000_000));
        java.util.UUID memberId = root(() -> {
            if (resets.countAllSince(now.minus(Duration.ofMinutes(1))) >= GLOBAL_PER_MINUTE) {
                throw new DomainException("Hay demasiadas solicitudes en este momento. Intenta en un minuto.");
            }
            Optional<PasswordReset> latest = resets.findLatest(email);
            if (latest.isPresent() && latest.get().createdAt().plus(Duration.ofMinutes(1)).isAfter(now)) return null;
            if (resets.countSince(email, now.minus(Duration.ofHours(1))) >= PER_HOUR) return null;
            return credentials.findByEmail(email).map(Credential::memberId).orElse(null);
        });
        if (memberId == null) return;
        // Primero el correo y después el registro: si el correo no sale, el pedido no cuenta para los límites.
        mailer.send(email, "Recupera tu contraseña de Kuador Go", """
                Pediste cambiar tu contraseña de Kuador Go.

                Abre este enlace para elegir una nueva:
                %s/recuperar?token=%s

                O escribe este código en la página de recuperación: %s

                El enlace y el código vencen en 30 minutos y sirven una sola vez. Si no lo pediste, ignora este correo:
                tu contraseña no cambia.
                """.formatted(webUrl, token, code));
        root(() -> {
            resets.save(PasswordReset.start(memberId, email, hash(token), hash(email + ":" + code), now));
            return null;
        });
    }

    /** Con el enlace del correo ({@code token}) o con el correo y su código, pone la contraseña nueva. */
    public void reset(String token, String rawEmail, String code, String newPassword) {
        Credential.checkPassword(newPassword);
        Instant now = clock.instant();
        String failure = root(() -> {
            PasswordReset reset;
            if (token != null && !token.isBlank()) {
                reset = resets.findByTokenHash(hash(token.strip())).filter(r -> r.usable(now))
                        .orElseThrow(() -> new DomainException("El enlace venció o ya se usó. Pide uno nuevo."));
            } else {
                String email = Credential.normalizeEmail(rawEmail);
                reset = resets.findLatest(email).filter(r -> r.usable(now))
                        .orElseThrow(() -> new DomainException("El código venció o se usaron todos los intentos. Pide uno nuevo."));
                // El intento se cuenta antes de comparar y en una sola sentencia: aunque lleguen muchos a la vez, no pasan
                // más de MAX_ATTEMPTS. Se guarda aunque falle (la transacción termina bien y el error se lanza afuera).
                int used = resets.claimAttempt(reset.id(), PasswordReset.MAX_ATTEMPTS);
                if (used < 0) return "El código venció o se usaron todos los intentos. Pide uno nuevo.";
                if (code == null || !MessageDigest.isEqual(hash(email + ":" + code.strip()).getBytes(StandardCharsets.UTF_8),
                        reset.codeHash().getBytes(StandardCharsets.UTF_8))) {
                    int left = PasswordReset.MAX_ATTEMPTS - used;
                    return left > 0 ? "El código no es correcto. Te quedan " + left + " intento(s)." : "El código no es correcto. Pide uno nuevo.";
                }
            }
            Credential c = credentials.findByMemberId(reset.memberId())
                    .orElseThrow(() -> new DomainException("Esa cuenta ya no tiene acceso. Pide a tu equipo que te lo dé de nuevo."));
            credentials.save(new Credential(c.memberId(), c.email(), hasher.hash(newPassword), c.createdAt(), now));
            // Se cierra todo lo que entra en su nombre: sesiones y conexiones de agentes locales o de clientes de IA.
            java.util.stream.Stream.concat(tokens.findActiveSessions(c.memberId()).stream(), tokens.findActiveRunnerTokens(c.memberId()).stream())
                    .forEach(t -> tokens.save(t.revoke(now)));
            resets.markUsed(reset.id(), now);
            return (String) null;
        });
        if (failure != null) throw new DomainException(failure);
    }

    /** Las credenciales y los pedidos son globales: se trabaja como root en una transacción corta. */
    private <T> T root(Supplier<T> work) {
        return TenantContext.asRoot(() -> tx.execute(s -> work.get()));
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
