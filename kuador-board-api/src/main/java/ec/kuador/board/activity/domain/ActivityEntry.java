package ec.kuador.board.activity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Una línea del historial. {@code field}/{@code from}/{@code to} dependen del tipo: en MOVED son el
 * estado, en ASSIGNED los ids de miembro, en UPDATED el campo editado y en COMMENTED {@code to} es un extracto.
 */
public record ActivityEntry(
        UUID id, UUID eventId, String issueKey, String projectKey, UUID actorId, ActivityType type,
        String field, String from, String to, Instant occurredAt) {}
