package ec.kuador.board.auth.infrastructure.persistence;

import ec.kuador.board.auth.domain.AccessToken;
import ec.kuador.board.auth.domain.TokenKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "access_token")
class AccessTokenEntity {

    @Id
    private UUID id;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "member_id")
    private UUID memberId;

    @Enumerated(EnumType.STRING)
    private TokenKind kind;

    private String name;

    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AccessTokenEntity() {}

    static AccessTokenEntity from(AccessToken t) {
        AccessTokenEntity e = new AccessTokenEntity();
        e.id = t.id();
        e.workspaceId = t.workspaceId();
        e.memberId = t.memberId();
        e.kind = t.kind();
        e.name = t.name();
        e.tokenHash = t.tokenHash();
        e.createdAt = t.createdAt();
        e.expiresAt = t.expiresAt();
        e.lastUsedAt = t.lastUsedAt();
        e.revokedAt = t.revokedAt();
        return e;
    }

    AccessToken toDomain() {
        return new AccessToken(id, workspaceId, memberId, kind, name, tokenHash, createdAt, expiresAt, lastUsedAt, revokedAt);
    }
}
