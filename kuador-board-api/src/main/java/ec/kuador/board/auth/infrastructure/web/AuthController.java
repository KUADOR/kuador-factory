package ec.kuador.board.auth.infrastructure.web;

import ec.kuador.board.shared.web.AuditHints;
import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.auth.domain.AccessToken;
import ec.kuador.board.member.MemberAccess;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuthController {

    private final AuthService auth;
    private final MemberApi members;
    private final ec.kuador.board.auth.application.PlatformAdmins platform;

    private final ec.kuador.board.shared.web.WebSecret web;

    /**
     * Poner una contraseña temporal a otra persona asigna un correo sin que su dueño lo confirme (alguien podría quedarse
     * con un correo ajeno). En la nube va apagado: se invita por correo. Para instalaciones propias, {@code true}.
     */
    private final boolean temporaryPasswords;

    AuthController(AuthService auth, MemberApi members, ec.kuador.board.auth.application.PlatformAdmins platform, ec.kuador.board.shared.web.WebSecret web,
            @org.springframework.beans.factory.annotation.Value("${board.auth.temporary-passwords:false}") boolean temporaryPasswords) {
        this.temporaryPasswords = temporaryPasswords;
        this.web = web;
        this.auth = auth;
        this.members = members;
        this.platform = platform;
    }

    // ---- Sesión ----

    @PostMapping("/api/auth/login")
    SessionResponse login(@Valid @RequestBody LoginRequest r, jakarta.servlet.http.HttpServletRequest request) {
        AuditHints.subject(r.email());
        AuthService.Session s = auth.login(r.email(), r.password(), web.clientIp(request));
        AuditHints.actor(new AuditHints.Actor(s.member().workspaceId(), s.member().id(), s.member().name(), AuditHints.Kind.HUMAN));
        return new SessionResponse(s.token(), s.expiresAt(), MeResponse.from(s.member(), platform.isAdmin(s.member().id())));
    }

    @PostMapping("/api/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@AuthenticationPrincipal AuthenticatedMember who) {
        auth.logout(who);
    }

    @GetMapping("/api/auth/me")
    MeResponse me(@AuthenticationPrincipal AuthenticatedMember who) {
        return MeResponse.from(auth.me(who), !who.agent() && !who.runner() && platform.isAdmin(who.memberId()));
    }

    @PutMapping("/api/auth/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@AuthenticationPrincipal AuthenticatedMember who, @Valid @RequestBody ChangePasswordRequest r) {
        auth.changePassword(who, r.currentPassword(), r.newPassword());
    }

    // ---- Accesos de personas ----

    @GetMapping("/api/auth/accounts")
    List<AccountResponse> accounts() {
        return auth.accounts().stream().map(a -> new AccountResponse(a.memberId(), a.email())).toList();
    }

    @PutMapping("/api/members/{memberId}/credentials")
    AccountResponse grantAccess(@PathVariable UUID memberId, @Valid @RequestBody GrantAccessRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        if (!temporaryPasswords) {
            throw new ec.kuador.board.shared.error.ForbiddenException("Invita a la persona por correo: así confirma que el correo es suyo y elige su contraseña.");
        }
        // Dar la cuenta de un administrador o dueño es entregar ese nivel: solo un dueño puede hacerlo.
        members.find(memberId).ifPresent(m -> members.requireCanManage(who.memberId(), m.access()));
        // Un correo de quien administra la plataforma solo entra con su propio registro (correo verificado), nunca asignado por otro.
        if (platform.isAdminEmail(r.email())) throw new ec.kuador.board.shared.error.ConflictException("Ese correo está reservado: esa persona debe registrarse ella misma.");
        auth.grantAccess(memberId, r.email(), r.password());
        return auth.accounts().stream().filter(a -> a.memberId().equals(memberId)).findFirst()
                .map(a -> new AccountResponse(a.memberId(), a.email())).orElseThrow();
    }

    /** Nivel de acceso. Solo quien administra; solo un dueño toca administradores y dueños; nadie cambia el suyo. */
    @PutMapping("/api/members/{memberId}/access")
    MemberAccessResponse setAccess(@PathVariable UUID memberId, @Valid @RequestBody SetAccessRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        MemberView m = members.setAccess(memberId, r.access(), who.memberId());
        return new MemberAccessResponse(m.id(), m.access());
    }

    record SetAccessRequest(@jakarta.validation.constraints.NotNull MemberAccess access) {}

    record MemberAccessResponse(UUID id, MemberAccess access) {}

    // ---- Conexiones de agentes locales de quien pide ----

    @GetMapping("/api/auth/runner-tokens")
    List<TokenResponse> runnerTokens(@AuthenticationPrincipal AuthenticatedMember who) {
        return auth.runnerTokens(who).stream().map(TokenResponse::from).toList();
    }

    @PostMapping("/api/auth/runner-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    IssuedTokenResponse createRunnerToken(@AuthenticationPrincipal AuthenticatedMember who, @Valid @RequestBody RunnerTokenRequest r) {
        AuthService.IssuedToken t = auth.createRunnerToken(who, r.name());
        return new IssuedTokenResponse(t.token(), TokenResponse.from(t.info()));
    }

    @DeleteMapping("/api/auth/runner-tokens/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeRunnerToken(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable UUID tokenId) {
        auth.revokeRunnerToken(who, tokenId);
    }

    record RunnerTokenRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 80) String name) {}

    // ---- Tokens de agentes ----

    @GetMapping("/api/auth/tokens")
    List<TokenResponse> tokens() {
        return auth.agentTokens().stream().map(TokenResponse::from).toList();
    }

    @PostMapping("/api/members/{memberId}/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    IssuedTokenResponse createToken(@PathVariable UUID memberId, @Valid @RequestBody CreateTokenRequest r) {
        AuthService.IssuedToken t = auth.createAgentToken(memberId, r.name());
        return new IssuedTokenResponse(t.token(), TokenResponse.from(t.info()));
    }

    @DeleteMapping("/api/auth/tokens/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeToken(@PathVariable UUID tokenId) {
        auth.revokeAgentToken(tokenId);
    }

    // ---- Errores propios del login ----

    @ExceptionHandler(AuthService.BadCredentialsException.class)
    ProblemDetail badCredentials(AuthService.BadCredentialsException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        pd.setTitle("No autenticado");
        return pd;
    }

    @ExceptionHandler(AuthService.LoginBlockedException.class)
    ProblemDetail blocked(AuthService.LoginBlockedException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        pd.setTitle("Demasiados intentos");
        return pd;
    }

    record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {}

    record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    record GrantAccessRequest(@NotBlank String email, @NotBlank String password) {}

    record CreateTokenRequest(@NotBlank @Size(max = 80) String name) {}

    /** {@code platformAdmin}: administra planes y licencias de la plataforma. */
    record MeResponse(UUID id, UUID workspaceId, String name, String kind, String role, String avatarColor, String access, boolean platformAdmin) {
        static MeResponse from(MemberView m, boolean platformAdmin) {
            return new MeResponse(m.id(), m.workspaceId(), m.name(), m.agent() ? "AGENT" : "HUMAN", m.role(), m.avatarColor(), m.access().name(),
                    platformAdmin);
        }
    }

    record SessionResponse(String token, Instant expiresAt, MeResponse member) {}

    record AccountResponse(UUID memberId, String email) {}

    record TokenResponse(UUID id, UUID memberId, String name, Instant createdAt, Instant lastUsedAt) {
        static TokenResponse from(AccessToken t) {
            return new TokenResponse(t.id(), t.memberId(), t.name(), t.createdAt(), t.lastUsedAt());
        }
    }

    record IssuedTokenResponse(String token, TokenResponse info) {}
}
