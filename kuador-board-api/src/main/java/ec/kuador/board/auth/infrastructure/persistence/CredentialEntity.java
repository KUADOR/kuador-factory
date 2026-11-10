package ec.kuador.board.auth.infrastructure.persistence;

import ec.kuador.board.auth.domain.Credential;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "credential")
class CredentialEntity {

    @Id
    @Column(name = "member_id")
    private UUID memberId;

    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected CredentialEntity() {}

    static CredentialEntity from(Credential c) {
        CredentialEntity e = new CredentialEntity();
        e.memberId = c.memberId();
        e.email = c.email();
        e.passwordHash = c.passwordHash();
        e.createdAt = c.createdAt();
        e.updatedAt = c.updatedAt();
        return e;
    }

    Credential toDomain() {
        return new Credential(memberId, email, passwordHash, createdAt, updatedAt);
    }
}
