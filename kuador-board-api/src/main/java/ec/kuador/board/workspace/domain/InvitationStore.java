package ec.kuador.board.workspace.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Invitaciones (SQL directo: al aceptar se busca por token, sin espacio en contexto). */
public interface InvitationStore {

    void save(Invitation invitation);

    Optional<Invitation> find(UUID workspaceId, UUID id);

    Optional<Invitation> findByTokenHash(String tokenHash);

    /** Pendientes (sin aceptar) del espacio, las más nuevas primero. */
    List<Invitation> pending(UUID workspaceId);

    /** Quita las pendientes de esa persona (al reenviar o cancelar). */
    void deletePending(UUID workspaceId, UUID memberId);

    void markAccepted(UUID id, Instant now);
}
