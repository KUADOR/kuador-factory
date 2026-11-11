package ec.kuador.board.notify.domain;

import java.time.Instant;
import java.util.UUID;

/** Un aviso en la cola de envíos, con su destino ya resuelto. */
public record Notice(
        UUID id, UUID workspaceId, UUID memberId, Channel.Kind channel, String address, String subject, String body, String link, int attempts,
        Instant createdAt) {}
