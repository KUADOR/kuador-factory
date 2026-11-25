package ec.kuador.board.auth.infrastructure.web;

import ec.kuador.board.shared.web.AuditHints;
import ec.kuador.board.auth.application.PasswordResetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Recuperar la contraseña sin sesión: pedir el correo (enlace + código) y poner la nueva. */
@RestController
class PasswordResetController {

    private final PasswordResetService resets;

    private static final org.springframework.web.server.ResponseStatusException TOO_MANY = new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos desde esta conexión. Espera unos minutos e inténtalo de nuevo.");
    /** Por IP: pedidos de código y confirmaciones (el código se adivina menos si nadie puede probar muy rápido). */
    private final ec.kuador.board.shared.web.RateLimiter perIp;
    private final ec.kuador.board.shared.web.RateLimiter confirmPerIp;
    private final ec.kuador.board.shared.web.WebSecret web;

    PasswordResetController(PasswordResetService resets, ec.kuador.board.shared.web.WebSecret web, ec.kuador.board.shared.web.RateLimits limits) {
        this.perIp = limits.limiter("clave-pedir-ip", 10, java.time.Duration.ofMinutes(10));
        this.confirmPerIp = limits.limiter("clave-confirmar-ip", 20, java.time.Duration.ofMinutes(10));
        this.web = web;
        this.resets = resets;
    }

    record ResetRequest(@NotBlank @Size(max = 254) String email) {}

    /** {@code token} (el del enlace) o {@code email} + {@code code}. */
    record ResetConfirm(@Size(max = 100) String token, @Size(max = 254) String email, @Size(max = 12) String code,
            @NotBlank @Size(max = 128) String password) {}

    @PostMapping("/api/auth/password-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void request(@Valid @RequestBody ResetRequest r, jakarta.servlet.http.HttpServletRequest http) {
        if (perIp.hit(web.clientIp(http), java.time.Instant.now())) throw TOO_MANY;
        AuditHints.subject(r.email());
        resets.request(r.email());
    }

    @PostMapping("/api/auth/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirm(@Valid @RequestBody ResetConfirm r, jakarta.servlet.http.HttpServletRequest http) {
        if (confirmPerIp.hit(web.clientIp(http), java.time.Instant.now())) throw TOO_MANY;
        AuditHints.subject(r.email());
        resets.reset(r.token(), r.email(), r.code(), r.password());
    }
}
