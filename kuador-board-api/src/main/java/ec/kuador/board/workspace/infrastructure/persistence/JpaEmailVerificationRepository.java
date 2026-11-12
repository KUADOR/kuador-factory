package ec.kuador.board.workspace.infrastructure.persistence;

import ec.kuador.board.workspace.domain.EmailVerification;
import ec.kuador.board.workspace.domain.EmailVerificationRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

interface SpringDataEmailVerifications extends JpaRepository<EmailVerificationEntity, UUID> {

    Optional<EmailVerificationEntity> findFirstByEmailOrderByCreatedAtDesc(String email);

    long countByEmailAndCreatedAtAfter(String email, Instant since);

    long countByCreatedAtAfter(Instant since);

    Optional<EmailVerificationEntity> findBySignupTokenHash(String hash);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update EmailVerificationEntity e set e.attempts = e.attempts + 1 where e.id = :id and e.attempts < :max")
    int claim(@org.springframework.data.repository.query.Param("id") UUID id, @org.springframework.data.repository.query.Param("max") int max);

    @org.springframework.data.jpa.repository.Query("select e.attempts from EmailVerificationEntity e where e.id = :id")
    int attempts(@org.springframework.data.repository.query.Param("id") UUID id);
}

@Repository
class JpaEmailVerificationRepository implements EmailVerificationRepository {

    private final SpringDataEmailVerifications jpa;

    JpaEmailVerificationRepository(SpringDataEmailVerifications jpa) {
        this.jpa = jpa;
    }

    @Override
    public EmailVerification save(EmailVerification v) {
        return jpa.save(EmailVerificationEntity.from(v)).toDomain();
    }

    @Override
    public Optional<EmailVerification> findLatest(String email) {
        return jpa.findFirstByEmailOrderByCreatedAtDesc(email).map(EmailVerificationEntity::toDomain);
    }

    @Override
    public long countSince(String email, Instant since) {
        return jpa.countByEmailAndCreatedAtAfter(email, since);
    }

    @Override
    public long countAllSince(Instant since) {
        return jpa.countByCreatedAtAfter(since);
    }

    @Override
    public int claimAttempt(UUID id, int max) {
        return jpa.claim(id, max) == 0 ? -1 : jpa.attempts(id);
    }

    @Override
    public Optional<EmailVerification> findBySignupTokenHash(String hash) {
        return jpa.findBySignupTokenHash(hash).map(EmailVerificationEntity::toDomain);
    }
}
