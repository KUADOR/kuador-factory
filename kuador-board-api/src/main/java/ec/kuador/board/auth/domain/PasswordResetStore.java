package ec.kuador.board.auth.domain;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetStore {

    void save(PasswordReset reset);

    Optional<PasswordReset> findByTokenHash(String tokenHash);

    Optional<PasswordReset> findLatest(String email);

    int countSince(String email, Instant since);

    int countAllSince(Instant since);

    void failedAttempt(java.util.UUID id);

    void markUsed(java.util.UUID id, Instant now);
}
