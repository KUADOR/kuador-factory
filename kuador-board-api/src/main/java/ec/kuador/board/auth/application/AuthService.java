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
    private final LoginAttempts attempts = new LoginAttempts(5, Duration.ofMinutes(15));
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
        Instant now = clock.instant();
        String email;
        try {
            email = Credential.normalizeEmail(rawEmail);
        } catch (DomainException e) {
            throw new BadCredentialsException();
        }
        if (attempts.isBlocked(email, now)) {
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
            attempts.recordFailure(email, now);
            throw new BadCredentialsException();
        }
        attempts.reset(email);
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
                    .filter(m -> m.agent() == (token.kind() == TokenKind.AGENT));
            if (member.isEmpty()) return Optional.<AuthenticatedMember>empty();
            if (token.lastUsedAt() == null || token.lastUsedAt().plus(TOUCH_EVERY).isBefore(now)) {
                tokens.save(token.touch(now));
            }
            MemberView m = member.get();
            return Optional.of(new AuthenticatedMember(m.id(), m.workspaceId(), m.name(), m.agent(), token.id()));
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
        tokens.findActiveSessions(who.memberId()).stream()
                .filter(t -> !t.id().equals(who.tokenId()))
                .forEach(t -> tokens.save(t.revoke(now)));
    }

    /** Da o restablece el acceso web de una persona del espacio actual. Cierra sus sesiones abiertas. */
    @Override
    @Transactional
    public void grantAccess(UUID memberId, String rawEmail, String password) {
        MemberView member = members.find(memberId).orElseThrow(() -> new NotFoundException("No existe el miembro " + memberId + "."));
        if (member.agent()) {
            throw new DomainException("Los agentes no entran con contraseña: crea un token de servicio.");
        }
        String email = Credential.normalizeEmail(rawEmail);
        Credential.checkPassword(password);
        if (credentials.existsByEmailForOtherMember(email, memberId)) {
            throw new ConflictException("Ese correo ya lo usa otra persona.");
        }
        Instant now = clock.instant();
        Instant createdAt = credentials.findByMemberId(memberId).map(Credential::createdAt).orElse(now);
        credentials.save(new Credential(memberId, email, hasher.hash(password), createdAt, now));
        tokens.findActiveSessions(memberId).forEach(t -> tokens.save(t.revoke(now)));
    }

    @Override
    public boolean emailInUse(String rawEmail) {
        String email = Credential.normalizeEmail(rawEmail);
        return Boolean.TRUE.equals(TenantContext.asRoot(() -> tx.execute(s -> credentials.findByEmail(email).isPresent())));
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
}
