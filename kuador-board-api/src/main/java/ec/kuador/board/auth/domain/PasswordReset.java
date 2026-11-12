package ec.kuador.board.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Pedido de recuperar la contraseña: vale 30 minutos, una sola vez y con hasta 5 intentos de código. */
public record PasswordReset(UUID id, UUID memberId, String email, String tokenHash, String codeHash, int attempts, Instant expiresAt,
        Instant usedAt, Instant createdAt) {

    public static final Duration VALID_FOR = Duration.ofMinutes(30);
    public static final int MAX_ATTEMPTS = 5;

    public static PasswordReset start(UUID memberId, String email, String tokenHash, String codeHash, Instant now) {
        return new PasswordReset(UUID.randomUUID(), memberId, email, tokenHash, codeHash, 0, now.plus(VALID_FOR), null, now);
    }

    public boolean usable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now) && attempts < MAX_ATTEMPTS;
    }
}
