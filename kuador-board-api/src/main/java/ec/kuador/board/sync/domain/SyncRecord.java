package ec.kuador.board.sync.domain;

import java.time.Instant;
import java.util.UUID;

/** Cómo estaba un documento en la última sincronización: versión del tablero y hash del archivo. */
public record SyncRecord(UUID documentId, int syncedVersion, String fileSha256, Instant syncedAt) {}
