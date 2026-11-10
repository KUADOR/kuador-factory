package ec.kuador.board.workspace.domain;

import java.time.Instant;
import java.util.Optional;

/** Puerto: verificaciones de correo (tabla global: el espacio todavía no existe). */
public interface EmailVerificationRepository {

    EmailVerification save(EmailVerification verification);

    /** La última solicitud de código para ese correo. */
    Optional<EmailVerification> findLatest(String email);

    long countSince(String email, Instant since);

    long countAllSince(Instant since);

    Optional<EmailVerification> findBySignupTokenHash(String hash);
}
