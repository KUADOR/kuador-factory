package ec.kuador.board.workspace.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Invitación por correo a una persona del equipo: vale 7 días y una vez; al aceptarla elige su contraseña. */
public record Invitation(UUID id, UUID workspaceId, UUID memberId, String email, String tokenHash, UUID invitedBy, Instant expiresAt,
        Instant acceptedAt, Instant createdAt) {

    public static final Duration VALID_FOR = Duration.ofDays(7);

    public static Invitation create(UUID workspaceId, UUID memberId, String email, String tokenHash, UUID invitedBy, Instant now) {
        return new Invitation(UUID.randomUUID(), workspaceId, memberId, email, tokenHash, invitedBy, now.plus(VALID_FOR), null, now);
    }

    public boolean usable(Instant now) {
        return acceptedAt == null && expiresAt.isAfter(now);
    }
}
