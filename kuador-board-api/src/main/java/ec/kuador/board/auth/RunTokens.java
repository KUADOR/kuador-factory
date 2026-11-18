package ec.kuador.board.auth;

import java.time.Duration;
import java.util.UUID;

/** Emite y revoca las credenciales de una sola ejecución del runner (ver {@link RunScope}). */
public interface RunTokens {

    record Issued(UUID id, String secret) {}

    /** Para la persona {@code memberId} del espacio, limitada a {@code scope} y que vence en {@code ttl}. */
    Issued issue(UUID workspaceId, UUID memberId, RunScope scope, Duration ttl);

    void revoke(UUID tokenId);
}
