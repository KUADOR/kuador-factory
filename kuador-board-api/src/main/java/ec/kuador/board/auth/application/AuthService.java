package ec.kuador.board.auth.application;

import ec.kuador.board.auth.AuthApi;
import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.auth.domain.AccessToken;
import ec.kuador.board.auth.domain.AccessTokenRepository;
import ec.kuador.board.auth.domain.Credential;
import ec.kuador.board.auth.domain.CredentialRepository;
import ec.kuador.board.auth.domain.PasswordHasher;
import ec.kuador.board.auth.domain.TokenKind;
import ec.kuador.board.auth.domain.Tokens;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.ForbiddenException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Autenticación. El login y la validación del token ocurren antes de saber el espacio de trabajo:
 * corren como root en su propia transacción (el espacio se fija al abrirla). Todo lo demás trabaja
 * en el espacio de quien hace la petición.
 */
@Service
public class AuthService implements AuthApi {

    static final Duration SESSION_TTL = Duration.ofDays(7);
    /** Evita escribir en base en cada petición: last_used_at se actualiza como mucho cada 5 minutos. */
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(5);

    private final CredentialRepository credentials;
    private final AccessTokenRepository tokens;
    private final PasswordHasher hasher;
    private final MemberApi members;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final LoginAttempts attempts = new LoginAttempts();
    private final String dummyHash;

    AuthService(CredentialRepository credentials, AccessTokenRepository tokens, PasswordHasher hasher, MemberApi members, Clock clock,
            PlatformTransactionManager txManager) {
        this.credentials = credentials;
        this.tokens = tokens;
        this.hasher = hasher;
        this.members = members;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        // Se compara contra este hash cuando el correo no existe, para no revelarlo por el tiempo de respuesta.
        this.dummyHash = hasher.hash("kuador-dummy-password-for-timing");
    }

    public record Session(String token, Instant expiresAt, MemberView member) {}

    public record IssuedToken(String token, AccessToken info) {}

    public record Account(UUID memberId, String email) {}

    public static class LoginBlockedException extends RuntimeException {
        LoginBlockedException() {
            super("Demasiados intentos fallidos. Espera 15 minutos e inténtalo de nuevo.");
        }
    }

    public static class BadCredentialsException extends RuntimeException {
        BadCredentialsException() {
            super("Correo o contraseña incorrectos.");
        }
    }

    // ---- Sin espacio todavía: corren como root ----

    public Session login(String rawEmail, String password) {
        return login(rawEmail, password, null);
    }

    /** {@code ip}: desde dónde se intenta (para frenar la fuerza bruta sin dejar que nadie bloquee a otro). */
    public Session login(String rawEmail, String password, String ip) {
        Instant now = clock.instant();
        String email;
        try {
            email = Credential.normalizeEmail(rawEmail);
        } catch (DomainException e) {
            throw new BadCredentialsException();
        }
        if (attempts.isBlocked(email, ip, now)) {
            throw new LoginBlockedException();
        }
        Session session = TenantContext.asRoot(() -> tx.execute(s -> {
            Optional<Credential> credential = credentials.findByEmail(email);
            boolean valid = hasher.matches(password == null ? "" : password, credential.map(Credential::passwordHash).orElse(dummyHash));
            Optional<MemberView> member = credential.flatMap(c -> members.find(c.memberId())).filter(m -> !m.agent());
            if (!valid || member.isEmpty()) return null;
            String secret = Tokens.newSecret(TokenKind.SESSION);
            Instant expiresAt = now.plus(SESSION_TTL);
            MemberView m = member.get();
            tokens.save(new AccessToken(UUID.randomUUID(), m.workspaceId(), m.id(), TokenKind.SESSION, "web", Tokens.hash(secret), now, expiresAt,
                    now, null));
            return new Session(secret, expiresAt, m);
        }));
        if (session == null) {
            attempts.recordFailure(email, ip, now);
            throw new BadCredentialsException();
        }
        attempts.reset(email, ip);
        return session;
    }

    /** Resuelve un token Bearer. Vacío si no existe, venció, fue revocado o su miembro ya no existe. */
    public Optional<AuthenticatedMember> authenticate(String secret) {
        if (secret == null || secret.isBlank()) return Optional.empty();
        Instant now = clock.instant();
        return TenantContext.asRoot(() -> tx.execute(s -> {
            Optional<AccessToken> found = tokens.findByHash(Tokens.hash(secret.strip())).filter(t -> t.isActive(now));
            if (found.isEmpty()) return Optional.<AuthenticatedMember>empty();
            AccessToken token = found.get();
            Optional<MemberView> member = members.find(token.memberId())
                    .filter(m -> m.workspaceId().equals(token.workspaceId()))
                    .filter(m -> m.agent() == (token.kind() == TokenKind.AGENT))
                    // Una conexión de agentes locales deja de valer si su persona pasa a solo lectura.
                    .filter(m -> token.kind() != TokenKind.RUNNER || !m.viewOnly());
            if (member.isEmpty()) return Optional.<AuthenticatedMember>empty();
            if (token.lastUsedAt() == null || token.lastUsedAt().plus(TOUCH_EVERY).isBefore(now)) {
                tokens.save(token.touch(now));
            }
            MemberView m = member.get();
            return Optional.of(new AuthenticatedMember(m.id(), m.workspaceId(), m.name(), m.agent(), token.id(), m.viewOnly(),
                    token.kind() == TokenKind.RUNNER));
        }));
    }

    // ---- En el espacio de quien pregunta ----

    @Transactional
    public void logout(AuthenticatedMember who) {
        tokens.findById(who.tokenId()).ifPresent(t -> tokens.save(t.revoke(clock.instant())));
    }

    @Transactional(readOnly = true)
    public MemberView me(AuthenticatedMember who) {
        return members.find(who.memberId()).orElseThrow(() -> new NotFoundException("Tu usuario ya no existe."));
    }

    /** Cambia la propia contraseña y cierra las demás sesiones abiertas. */
    @Transactional
    public void changePassword(AuthenticatedMember who, String currentPassword, String newPassword) {
        Credential credential = credentials.findByMemberId(who.memberId())
                .orElseThrow(() -> new DomainException("Tu usuario no tiene contraseña."));
        if (!hasher.matches(currentPassword == null ? "" : currentPassword, credential.passwordHash())) {
            throw new DomainException("La contraseña actual no es correcta.");
        }
        Credential.checkPassword(newPassword);
        Instant now = clock.instant();
        credentials.save(new Credential(credential.memberId(), credential.email(), hasher.hash(newPassword), credential.createdAt(), now));
        revokeAll(who.memberId(), who.tokenId(), now);
    }

    /**
     * Cierra todo lo que entra en nombre de la persona (sesiones y conexiones de agentes locales o de clientes de IA),
     * salvo {@code keep} (la sesión desde la que cambió su contraseña).
     */
    public void revokeAll(UUID memberId, UUID keep, Instant now) {
        java.util.stream.Stream.concat(tokens.findActiveSessions(memberId).stream(), tokens.findActiveRunnerTokens(memberId).stream())
                .filter(t -> !t.id().equals(keep))
                .forEach(t -> tokens.save(t.revoke(now)));
    }

    /**
     * Da el primer acceso web a una persona del espacio actual (alta, invitación aceptada o contraseña temporal). Nunca
     * sobre alguien que ya tiene acceso: así nadie del espacio puede quedarse con la cuenta de otro. Quien olvidó su
     * contraseña la recupera por correo.
     */
    @Override
    @Transactional
    public void grantAccess(UUID memberId, String rawEmail, String password) {
        MemberView member = members.find(memberId).orElseThrow(() -> new NotFoundException("No existe el miembro " + memberId + "."));
        if (member.agent()) {
            throw new DomainException("Los agentes no entran con contraseña: crea un token de servicio.");
        }
        String email = Credential.normalizeEmail(rawEmail);
        Credential.checkPassword(password);
        if (credentials.findByMemberId(memberId).isPresent()) {
            throw new ConflictException(member.name() + " ya tiene acceso. Si olvidó su contraseña, que la recupere con «¿Olvidaste tu contraseña?» al iniciar sesión.");
        }
        if (credentials.existsByEmailForOtherMember(email, memberId)) {
            throw new ConflictException("Ese correo ya lo usa otra persona.");
        }
        Instant now = clock.instant();
        credentials.save(new Credential(memberId, email, hasher.hash(password), now, now));
        revokeAll(memberId, null, now);
    }

    @Override
    public boolean emailInUse(String rawEmail) {
        String email = Credential.normalizeEmail(rawEmail);
        return Boolean.TRUE.equals(TenantContext.asRoot(() -> tx.execute(s -> credentials.findByEmail(email).isPresent())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> emailOf(UUID memberId) {
        return credentials.findByMemberId(memberId).map(Credential::email);
    }

    /** Accesos de las personas del espacio actual (las credenciales son globales: se filtran por miembro). */
    @Transactional(readOnly = true)
    public List<Account> accounts() {
        List<UUID> people = members.humans().stream().map(MemberView::id).toList();
        return people.isEmpty() ? List.of()
                : credentials.findByMemberIds(people).stream().map(c -> new Account(c.memberId(), c.email())).toList();
    }

    @Transactional
    public IssuedToken createAgentToken(UUID memberId, String name) {
        MemberView member = members.find(memberId).orElseThrow(() -> new NotFoundException("No existe el miembro " + memberId + "."));
        if (!member.agent()) {
            throw new DomainException("Los tokens de servicio son solo para agentes.");
        }
        if (name == null || name.isBlank() || name.length() > 80) {
            throw new DomainException("Ponle un nombre al token (máximo 80 caracteres), por ejemplo \"runner local\".");
        }
        Instant now = clock.instant();
        String secret = Tokens.newSecret(TokenKind.AGENT);
        AccessToken saved = tokens.save(new AccessToken(UUID.randomUUID(), member.workspaceId(), memberId, TokenKind.AGENT, name.strip(),
                Tokens.hash(secret), now, null, null, null));
        return new IssuedToken(secret, saved);
    }

    @Transactional(readOnly = true)
    public List<AccessToken> agentTokens() {
        return tokens.findActiveAgentTokens(TenantContext.require());
    }

    @Transactional
    public void revokeAgentToken(UUID tokenId) {
        UUID workspace = TenantContext.require();
        AccessToken token = tokens.findById(tokenId)
                .filter(t -> t.kind() == TokenKind.AGENT && t.workspaceId().equals(workspace))
                .orElseThrow(() -> new NotFoundException("No existe el token " + tokenId + "."));
        tokens.save(token.revoke(clock.instant()));
    }

    // ---- Conexiones de agentes locales (el cliente de IA de una persona: Claude Code, Codex...) ----

    /** Emite la conexión de agentes locales de quien pide; el secreto se muestra una sola vez. */
    @Transactional
    public IssuedToken createRunnerToken(AuthenticatedMember who, String name) {
        if (who.agent() || who.runner()) throw new ForbiddenException("Solo una persona puede conectar sus agentes locales.");
        if (who.viewOnly()) throw new ForbiddenException("Con acceso de solo lectura no se pueden poner agentes a trabajar.");
        if (name == null || name.isBlank() || name.length() > 80) {
            throw new DomainException("Ponle un nombre a la conexión (máximo 80 caracteres), por ejemplo \"Laptop con Codex\".");
        }
        Instant now = clock.instant();
        String secret = Tokens.newSecret(TokenKind.RUNNER);
        AccessToken saved = tokens.save(new AccessToken(UUID.randomUUID(), who.workspaceId(), who.memberId(), TokenKind.RUNNER, name.strip(),
                Tokens.hash(secret), now, null, null, null));
        return new IssuedToken(secret, saved);
    }

    /**
     * Conexión emitida por un inicio de sesión OAuth: el canje llega sin sesión, así que se resuelve la persona
     * en su espacio y se aplican las mismas reglas que al crearla a mano.
     */
    public IssuedToken issueRunnerToken(UUID workspaceId, UUID memberId, String name) {
        return TenantContext.call(workspaceId, () -> tx.execute(s -> {
            MemberView m = members.find(memberId).filter(x -> x.workspaceId().equals(workspaceId))
                    .orElseThrow(() -> new ForbiddenException("La persona que aprobó ya no está en el espacio."));
            AuthenticatedMember who = new AuthenticatedMember(m.id(), m.workspaceId(), m.name(), m.agent(), null, m.viewOnly());
            return createRunnerToken(who, name.length() > 80 ? name.substring(0, 80) : name);
        }));
    }

    @Transactional(readOnly = true)
    public List<AccessToken> runnerTokens(AuthenticatedMember who) {
        return tokens.findActiveRunnerTokens(who.memberId());
    }

    /** Cada persona revoca solo sus propias conexiones. */
    @Transactional
    public void revokeRunnerToken(AuthenticatedMember who, UUID tokenId) {
        AccessToken token = tokens.findById(tokenId)
                .filter(t -> t.kind() == TokenKind.RUNNER && t.memberId().equals(who.memberId()))
                .orElseThrow(() -> new NotFoundException("No existe la conexión " + tokenId + "."));
        tokens.save(token.revoke(clock.instant()));
    }
}
