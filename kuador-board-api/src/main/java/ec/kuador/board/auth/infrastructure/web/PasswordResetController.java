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

    PasswordResetController(PasswordResetService resets) {
        this.resets = resets;
    }

    record ResetRequest(@NotBlank @Size(max = 254) String email) {}

    /** {@code token} (el del enlace) o {@code email} + {@code code}. */
    record ResetConfirm(@Size(max = 100) String token, @Size(max = 254) String email, @Size(max = 12) String code,
            @NotBlank @Size(max = 128) String password) {}

    @PostMapping("/api/auth/password-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void request(@Valid @RequestBody ResetRequest r) {
        AuditHints.subject(r.email());
        resets.request(r.email());
    }

    @PostMapping("/api/auth/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirm(@Valid @RequestBody ResetConfirm r) {
        AuditHints.subject(r.email());
        resets.reset(r.token(), r.email(), r.code(), r.password());
    }
}
