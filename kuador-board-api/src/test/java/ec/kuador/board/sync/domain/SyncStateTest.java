package ec.kuador.board.sync.domain;

import static ec.kuador.board.sync.domain.SyncState.BOARD_AHEAD;
import static ec.kuador.board.sync.domain.SyncState.BOARD_ONLY;
import static ec.kuador.board.sync.domain.SyncState.CONFLICT;
import static ec.kuador.board.sync.domain.SyncState.FILE_AHEAD;
import static ec.kuador.board.sync.domain.SyncState.FILE_DELETED;
import static ec.kuador.board.sync.domain.SyncState.SYNCED;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SyncStateTest {

    private static final Optional<SyncRecord> AT_V2_WITH_A =
            Optional.of(new SyncRecord(UUID.randomUUID(), 2, SyncState.sha256("A"), Instant.EPOCH));

    @Test
    void threeWayRules() {
        assertThat(SyncState.of(AT_V2_WITH_A, 2, "A", Optional.of("A"))).isEqualTo(SYNCED);
        assertThat(SyncState.of(AT_V2_WITH_A, 3, "B", Optional.of("A"))).as("solo cambió el tablero").isEqualTo(BOARD_AHEAD);
        assertThat(SyncState.of(AT_V2_WITH_A, 2, "A", Optional.of("C"))).as("solo cambió el archivo").isEqualTo(FILE_AHEAD);
        assertThat(SyncState.of(AT_V2_WITH_A, 3, "B", Optional.of("C"))).as("ambos").isEqualTo(CONFLICT);
        assertThat(SyncState.of(AT_V2_WITH_A, 3, "B", Optional.of("B"))).as("ambos, pero iguales").isEqualTo(SYNCED);
    }

    @Test
    void missingSides() {
        assertThat(SyncState.of(Optional.empty(), 1, "A", Optional.empty())).isEqualTo(BOARD_ONLY);
        assertThat(SyncState.of(AT_V2_WITH_A, 2, "A", Optional.empty())).isEqualTo(FILE_DELETED);
        assertThat(SyncState.of(AT_V2_WITH_A, 3, "B", Optional.empty())).isEqualTo(BOARD_AHEAD);
        assertThat(SyncState.of(Optional.empty(), 1, "A", Optional.of("A"))).as("nunca sincronizado pero idéntico").isEqualTo(SYNCED);
        assertThat(SyncState.of(Optional.empty(), 1, "A", Optional.of("Z"))).as("nunca sincronizado y distinto").isEqualTo(CONFLICT);
    }

    @Test
    void conflictsNeedAnExplicitChoice() {
        assertThat(CONFLICT.canExport(false)).isFalse();
        assertThat(CONFLICT.canImport(false)).isFalse();
        assertThat(CONFLICT.canExport(true)).isTrue();
        assertThat(CONFLICT.canImport(true)).isTrue();
        assertThat(FILE_AHEAD.canExport(true)).as("force no permite pisar cambios del repo sin conflicto").isFalse();
        assertThat(BOARD_AHEAD.canImport(true)).isFalse();
    }
}
