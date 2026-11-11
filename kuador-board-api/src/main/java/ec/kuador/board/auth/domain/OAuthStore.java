package ec.kuador.board.auth.domain;

import java.time.Instant;
import java.util.Optional;

/** Puerto: clientes OAuth y códigos de autorización. Son globales (no dependen del espacio en contexto). */
public interface OAuthStore {

    void saveClient(OAuthClient client);

    Optional<OAuthClient> findClient(String clientId);

    void saveCode(AuthorizationCode code);

    Optional<AuthorizationCode> findCode(String codeHash);

    /** Marca el código como usado; falso si otro canje lo usó antes (carrera). */
    boolean markUsed(String codeHash, Instant now);

    /** Borra los códigos vencidos. */
    int deleteExpiredCodes(Instant now);
}
