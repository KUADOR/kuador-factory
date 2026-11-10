package ec.kuador.board.sync.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Estado de un documento entre el tablero y el repositorio. */
public enum SyncState {
    /** Mismo contenido en ambos lados. */
    SYNCED,
    /** Cambió en el tablero desde la última sincronización: exportar. */
    BOARD_AHEAD,
    /** Cambió en el repositorio: importar. */
    FILE_AHEAD,
    /** Cambió en ambos lados (o difieren y nunca se sincronizaron): una persona decide. */
    CONFLICT,
    /** Nunca se exportó: el archivo no existe. */
    BOARD_ONLY,
    /** Se sincronizó y luego el archivo se borró del repo, sin cambios en el tablero. */
    FILE_DELETED;

    /**
     * Regla de tres vías: compara el tablero y el archivo con cómo estaban en la última
     * sincronización. Si el contenido es idéntico, está sincronizado aunque falte el registro.
     */
    public static SyncState of(Optional<SyncRecord> last, int boardVersion, String boardContent, Optional<String> file) {
        if (file.isEmpty()) {
            if (last.isEmpty()) return BOARD_ONLY;
            return last.get().syncedVersion() == boardVersion ? FILE_DELETED : BOARD_AHEAD;
        }
        if (file.get().equals(boardContent)) return SYNCED;
        if (last.isEmpty()) return CONFLICT;
        boolean boardChanged = boardVersion != last.get().syncedVersion();
        boolean fileChanged = !sha256(file.get()).equals(last.get().fileSha256());
        if (boardChanged && fileChanged) return CONFLICT;
        if (boardChanged) return BOARD_AHEAD;
        if (fileChanged) return FILE_AHEAD;
        return CONFLICT; // registro incoherente: mejor que decida una persona
    }

    public boolean canExport(boolean force) {
        return this == BOARD_AHEAD || this == BOARD_ONLY || this == FILE_DELETED || (force && this == CONFLICT);
    }

    public boolean canImport(boolean force) {
        return this == FILE_AHEAD || (force && this == CONFLICT);
    }

    public static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
