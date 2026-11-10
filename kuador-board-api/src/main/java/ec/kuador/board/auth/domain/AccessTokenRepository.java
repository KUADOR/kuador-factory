package ec.kuador.board.auth.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessTokenRepository {

    Optional<AccessToken> findByHash(String tokenHash);

    Optional<AccessToken> findById(UUID id);

    /** Tokens de agentes no revocados de un espacio. */
    List<AccessToken> findActiveAgentTokens(UUID workspaceId);

    List<AccessToken> findActiveSessions(UUID memberId);

    AccessToken save(AccessToken token);
}
