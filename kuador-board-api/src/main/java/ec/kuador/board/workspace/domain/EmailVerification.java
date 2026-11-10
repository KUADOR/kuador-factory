package ec.kuador.board.workspace.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Verificación de un correo para crear un espacio. Primero se envía un código de 6 dígitos; al
 * acertarlo se emite un token de alta que permite terminar el onboarding. Solo se guardan hashes.
 */
public record EmailVerification(
        UUID id, String email, String codeHash, int attempts, Instant expiresAt, Instant verifiedAt,
        String signupTokenHash, Instant signupExpiresAt, Instant usedAt, Instant createdAt) {

    public static final Duration CODE_TTL = Duration.ofMinutes(10);
    public static final Duration SIGNUP_TTL = Duration.ofMinutes(30);
    public static final int MAX_ATTEMPTS = 5;

    public static EmailVerification start(String email, String codeHash, Instant now) {
        return new EmailVerification(UUID.randomUUID(), email, codeHash, 0, now.plus(CODE_TTL), null, null, null, null, now);
    }

    public boolean codeUsable(Instant now) {
        return verifiedAt == null && attempts < MAX_ATTEMPTS && now.isBefore(expiresAt);
    }

    public EmailVerification failedAttempt() {
        return new EmailVerification(id, email, codeHash, attempts + 1, expiresAt, verifiedAt, signupTokenHash, signupExpiresAt, usedAt, createdAt);
    }

    public EmailVerification verified(String tokenHash, Instant now) {
        return new EmailVerification(id, email, codeHash, attempts, expiresAt, now, tokenHash, now.plus(SIGNUP_TTL), usedAt, createdAt);
    }

    public boolean signupUsable(Instant now) {
        return verifiedAt != null && usedAt == null && signupExpiresAt != null && now.isBefore(signupExpiresAt);
    }

    public EmailVerification used(Instant now) {
        return new EmailVerification(id, email, codeHash, attempts, expiresAt, verifiedAt, signupTokenHash, signupExpiresAt, now, createdAt);
    }
}
