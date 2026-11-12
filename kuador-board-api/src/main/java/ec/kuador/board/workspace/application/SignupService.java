package ec.kuador.board.workspace.application;

import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.workspace.domain.EmailVerification;
import ec.kuador.board.workspace.domain.EmailVerificationRepository;
import ec.kuador.board.workspace.domain.VerificationMailer;
import ec.kuador.board.workspace.domain.Workspace;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import ec.kuador.board.auth.AuthApi;
import ec.kuador.board.billing.BillingApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Alta pública de espacios (modo SaaS) en tres pasos: pedir un código al correo, verificarlo y
 * crear el espacio con su marca y la persona dueña. Sin un correo verificado no se crea nada.
 *
 * <p>Límites contra abuso: un código nuevo por minuto y cinco por hora por correo, cinco intentos
 * por código y un tope global de envíos por minuto. Las respuestas no revelan si un correo ya
 * tiene cuenta hasta que la persona demuestra que es suyo.
 */
@Service
public class SignupService {

    static final Duration RESEND_AFTER = Duration.ofMinutes(1);
    static final int CODES_PER_HOUR = 5;
    static final int GLOBAL_CODES_PER_MINUTE = 30;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]{1,190}\\.[^\\s@]{2,63}$");

    private final EmailVerificationRepository verifications;
    private final VerificationMailer mailer;
    private final WorkspaceService workspaces;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final BillingApi billing;
    private final AuthApi auth;
    private final ec.kuador.board.catalog.CatalogApi catalog;
    private final ec.kuador.board.project.ProjectApi projects;
    private final SecureRandom random = new SecureRandom();

    SignupService(EmailVerificationRepository verifications, VerificationMailer mailer, WorkspaceService workspaces,
            PlatformTransactionManager txManager, Clock clock, BillingApi billing, AuthApi auth, ec.kuador.board.catalog.CatalogApi catalog,
            ec.kuador.board.project.ProjectApi projects) {
        this.catalog = catalog;
        this.projects = projects;
        this.billing = billing;
        this.auth = auth;
        this.verifications = verifications;
        this.mailer = mailer;
        this.workspaces = workspaces;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public record Company(String name, String slug, String description, String brandColor, String brandOnColor, String logo) {}

    /** Paso 1: envía un código de 6 dígitos al correo. */
    public void requestCode(String rawEmail) {
        String email = normalize(rawEmail);
        Instant now = clock.instant();
        String code = "%06d".formatted(random.nextInt(1_000_000));
        root(() -> {
            if (verifications.countAllSince(now.minus(Duration.ofMinutes(1))) >= GLOBAL_CODES_PER_MINUTE) {
                throw new TooManyRequestsException("Hay demasiadas solicitudes en este momento. Intenta en un minuto.");
            }
            var latest = verifications.findLatest(email);
            if (latest.isPresent() && latest.get().createdAt().plus(RESEND_AFTER).isAfter(now)) {
                throw new TooManyRequestsException("Ya te enviamos un código. Espera un minuto para pedir otro.");
            }
            if (verifications.countSince(email, now.minus(Duration.ofHours(1))) >= CODES_PER_HOUR) {
                throw new TooManyRequestsException("Pediste demasiados códigos. Intenta de nuevo en una hora.");
            }
            return null;
        });
        // Primero el correo y después el registro: si el correo no sale, el pedido no cuenta para los límites
        // (la persona puede reintentar enseguida) y no queda un código que nadie recibió.
        mailer.sendCode(email, code);
        root(() -> verifications.save(EmailVerification.start(email, hash(email + ":" + code), now)));
    }

    /** Paso 2: comprueba el código. Devuelve el token de alta (se muestra una sola vez). */
    public String verifyCode(String rawEmail, String code) {
        String email = normalize(rawEmail);
        Instant now = clock.instant();
        // El intento fallido se guarda (la transacción termina bien) y el error se lanza afuera: si se lanzara
        // adentro, el rollback borraría el intento y el código se podría adivinar sin límite.
        String[] result = root(() -> {
            EmailVerification v = verifications.findLatest(email).filter(x -> x.codeUsable(now))
                    .orElseThrow(() -> new DomainException("El código venció o se usaron todos los intentos. Pide uno nuevo."));
            if (code == null || !MessageDigest.isEqual(hash(email + ":" + code.strip()).getBytes(StandardCharsets.UTF_8),
                    v.codeHash().getBytes(StandardCharsets.UTF_8))) {
                EmailVerification failed = verifications.save(v.failedAttempt());
                int left = EmailVerification.MAX_ATTEMPTS - failed.attempts();
                return new String[] {null, left > 0 ? "El código no es correcto. Te quedan " + left + " intento(s)." : "El código no es correcto. Pide uno nuevo."};
            }
            // Ya demostró que el correo es suyo: si tiene cuenta se le dice ahora, no al final del alta.
            if (auth.emailInUse(email)) return new String[] {null, null};
            String token = newToken();
            verifications.save(v.verified(hash(token), now));
            return new String[] {token, null};
        });
        if (result[1] != null) throw new DomainException(result[1]);
        if (result[0] == null) throw new AccountExistsException();
        return result[0];
    }

    /**
     * Paso 3: crea el espacio con su marca y la persona dueña (con el correo verificado) y su plan: los de pago
     * arrancan con su prueba; {@code planCode} nulo = el plan gratuito.
     */
    public Workspace complete(String signupToken, Company company, String ownerName, String password, String planCode) {
        return complete(signupToken, company, ownerName, password, planCode, null);
    }

    /** Igual, con el paquete por industria: se guarda y, si trae tablero inicial, el espacio arranca con él y su equipo. */
    public Workspace complete(String signupToken, Company company, String ownerName, String password, String planCode, String packageCode) {
        Instant now = clock.instant();
        EmailVerification v = root(() -> verifications.findBySignupTokenHash(hash(signupToken == null ? "" : signupToken)))
                .filter(x -> x.signupUsable(now))
                .orElseThrow(() -> new DomainException("La verificación del correo venció. Vuelve a pedir un código."));
        String slug = company.slug() == null || company.slug().isBlank() ? Workspace.slugOf(company.name()) : company.slug().strip();
        Workspace workspace = workspaces.provision(
                new WorkspaceService.Brand(slug, company.name(), company.description(), company.brandColor(), company.brandOnColor(), company.logo()),
                new WorkspaceService.Owner(ownerName, v.email(), password));
        root(() -> {
            billing.subscribe(workspace.id(), planCode);
            return verifications.save(v.used(now));
        });
        starter(workspace, packageCode);
        return workspace;
    }

    /** El paquete elegido y su tablero inicial. Si el tablero no se puede crear (p. ej. el plan no lo permite), el alta sigue igual. */
    private void starter(Workspace workspace, String packageCode) {
        if (packageCode == null || packageCode.isBlank()) return;
        try {
            TenantContext.run(workspace.id(), () -> tx.executeWithoutResult(s -> catalog.choosePackage(packageCode)
                    .ifPresent(b -> projects.createBoard(b.key(), b.name(), b.templateKey()))));
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(SignupService.class).warn("No se pudo crear el tablero inicial de {}: {}", workspace.slug(), e.getMessage());
        }
    }

    /** Identificador sugerido y libre para un nombre de empresa. */
    public String suggestSlug(String name) {
        return freeSlug(Workspace.slugOf(name));
    }

    private String freeSlug(String base) {
        if (!root(() -> workspaces.slugTaken(base))) return base;
        for (int i = 2; i < 100; i++) {
            String c = candidate(base, i);
            if (!root(() -> workspaces.slugTaken(c))) return c;
        }
        return base + "-" + newToken().substring(0, 6).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "x");
    }

    private static String candidate(String base, int n) {
        String suffix = "-" + n;
        return (base.length() + suffix.length() > 40 ? base.substring(0, 40 - suffix.length()) : base) + suffix;
    }

    static String normalize(String raw) {
        String email = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(email).matches()) throw new DomainException("Escribe un correo válido.");
        return email;
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

    /** La tabla de verificaciones es global: se trabaja como root en una transacción corta. */
    private <T> T root(java.util.function.Supplier<T> work) {
        return TenantContext.asRoot(() -> tx.execute(s -> work.get()));
    }

    /** El correo verificado ya tiene cuenta: debe entrar o recuperar su contraseña. */
    public static class AccountExistsException extends RuntimeException {
        AccountExistsException() {
            super("Ese correo ya tiene una cuenta en Kuador Go. Entra con tu contraseña o recupérala si no la recuerdas.");
        }
    }

    public static class TooManyRequestsException extends RuntimeException {
        TooManyRequestsException(String message) {
            super(message);
        }
    }
}
