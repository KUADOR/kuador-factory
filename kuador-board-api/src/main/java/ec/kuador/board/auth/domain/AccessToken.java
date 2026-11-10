package ec.kuador.board.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record AccessToken(
        UUID id, UUID workspaceId, UUID memberId, TokenKind kind, String name, String tokenHash, Instant createdAt,
        Instant expiresAt, Instant lastUsedAt, Instant revokedAt) {

    public boolean isActive(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    public AccessToken revoke(Instant now) {
        return revokedAt != null ? this : new AccessToken(id, workspaceId, memberId, kind, name, tokenHash, createdAt, expiresAt, lastUsedAt, now);
    }

    public AccessToken touch(Instant now) {
        return new AccessToken(id, workspaceId, memberId, kind, name, tokenHash, createdAt, expiresAt, now, revokedAt);
    }
}
