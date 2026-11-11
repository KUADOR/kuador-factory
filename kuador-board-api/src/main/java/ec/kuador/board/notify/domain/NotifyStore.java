package ec.kuador.board.notify.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puerto: preferencias, vínculos de Telegram y la cola de avisos. */
public interface NotifyStore {

    Optional<NotificationSettings> settings(UUID memberId);

    void saveSettings(UUID workspaceId, NotificationSettings settings, Instant now);

    void saveLinkCode(String codeHash, UUID workspaceId, UUID memberId, Instant expiresAt);

    /** Consume el código (una sola vez): espacio y persona, si existía y no venció. */
    Optional<LinkTarget> consumeLinkCode(String codeHash, Instant now);

    record LinkTarget(UUID workspaceId, UUID memberId) {}

    void enqueue(Notice notice, Instant now);

    /** Avisos listos para enviar, bloqueados para este proceso (otras réplicas los saltan). Llamar dentro de una transacción. */
    List<Notice> lockDue(Instant now, int limit);

    void markSent(UUID id, Instant now);

    void markRetry(UUID id, int attempts, Instant nextAttempt, String error);

    void markFailed(UUID id, int attempts, String error);
}
