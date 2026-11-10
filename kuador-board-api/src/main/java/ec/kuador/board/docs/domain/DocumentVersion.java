package ec.kuador.board.docs.domain;

import java.time.Instant;
import java.util.UUID;

public record DocumentVersion(UUID documentId, int version, String content, UUID authorId, String note, Instant createdAt) {}
