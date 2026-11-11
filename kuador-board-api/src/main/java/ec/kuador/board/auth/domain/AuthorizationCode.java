package ec.kuador.board.auth.domain;

import java.time.Instant;
import java.util.UUID;

/** Código de autorización emitido a un cliente para una persona; vale una sola vez y por poco tiempo. */
public record AuthorizationCode(
        String codeHash, String clientId, UUID workspaceId, UUID memberId, String redirectUri, String codeChallenge, Instant expiresAt, Instant usedAt) {

    public boolean usable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }
}
