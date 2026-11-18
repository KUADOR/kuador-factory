package ec.kuador.board.auth.domain;

import java.time.Instant;
import java.util.UUID;

/** {@code scope}: alcance de un token RUN («CLAVE|ROL»); nulo en los demás. */
public record AccessToken(
        UUID id, UUID workspaceId, UUID memberId, TokenKind kind, String name, String tokenHash, Instant createdAt,
        Instant expiresAt, Instant lastUsedAt, Instant revokedAt, String scope) {

    public AccessToken(UUID id, UUID workspaceId, UUID memberId, TokenKind kind, String name, String tokenHash, Instant createdAt,
            Instant expiresAt, Instant lastUsedAt, Instant revokedAt) {
        this(id, workspaceId, memberId, kind, name, tokenHash, createdAt, expiresAt, lastUsedAt, revokedAt, null);
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    public AccessToken revoke(Instant now) {
        return revokedAt != null ? this : new AccessToken(id, workspaceId, memberId, kind, name, tokenHash, createdAt, expiresAt, lastUsedAt, now, scope);
    }

    public AccessToken touch(Instant now) {
        return new AccessToken(id, workspaceId, memberId, kind, name, tokenHash, createdAt, expiresAt, now, revokedAt, scope);
    }
}
