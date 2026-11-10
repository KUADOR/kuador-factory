package ec.kuador.board.workspace.infrastructure.persistence;

import ec.kuador.board.workspace.domain.EmailVerification;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Tabla global (sin @TenantId): la verificación ocurre antes de que exista el espacio. */
@Entity
@Table(name = "email_verification")
class EmailVerificationEntity {

    @Id
    UUID id;

    String email;

    @Column(name = "code_hash")
    String codeHash;

    int attempts;

    @Column(name = "expires_at")
    Instant expiresAt;

    @Column(name = "verified_at")
    Instant verifiedAt;

    @Column(name = "signup_token_hash")
    String signupTokenHash;

    @Column(name = "signup_expires_at")
    Instant signupExpiresAt;

    @Column(name = "used_at")
    Instant usedAt;

    @Column(name = "created_at")
    Instant createdAt;

    protected EmailVerificationEntity() {}

    static EmailVerificationEntity from(EmailVerification v) {
        EmailVerificationEntity e = new EmailVerificationEntity();
        e.id = v.id();
        e.email = v.email();
        e.codeHash = v.codeHash();
        e.attempts = v.attempts();
        e.expiresAt = v.expiresAt();
        e.verifiedAt = v.verifiedAt();
        e.signupTokenHash = v.signupTokenHash();
        e.signupExpiresAt = v.signupExpiresAt();
        e.usedAt = v.usedAt();
        e.createdAt = v.createdAt();
        return e;
    }

    EmailVerification toDomain() {
        return new EmailVerification(id, email, codeHash, attempts, expiresAt, verifiedAt, signupTokenHash, signupExpiresAt, usedAt, createdAt);
    }
}
