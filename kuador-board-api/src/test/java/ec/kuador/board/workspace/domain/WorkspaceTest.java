package ec.kuador.board.workspace.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.shared.error.DomainException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WorkspaceTest {

    private static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    @Test
    void brandingIsNormalizedAndDefaultsToTheKuadorSun() {
        Workspace w = Workspace.create("clinica-andes", "  Clínica Andes ", " Salud ", null, null, PNG, Instant.EPOCH);
        assertThat(w.name()).isEqualTo("Clínica Andes");
        assertThat(w.description()).isEqualTo("Salud");
        assertThat(w.brandColor()).isEqualTo("#ffb81c");
        assertThat(w.brandOnColor()).as("automático").isNull();
        Workspace rebranded = w.withBranding("Andes", null, "#E11D48", "#FFFFFF", null);
        assertThat(rebranded.brandColor()).isEqualTo("#e11d48");
        assertThat(rebranded.brandOnColor()).isEqualTo("#ffffff");
    }

    @Test
    void rejectsBadColorsAndUnsafeLogos() {
        assertThatThrownBy(() -> Workspace.create("acme", "Acme", null, "red", null, null, Instant.EPOCH)).isInstanceOf(DomainException.class)
                .hasMessageContaining("hexadecimal");
        assertThatThrownBy(() -> Workspace.create("acme", "Acme", null, null, null, "data:image/svg+xml;base64,PHN2Zz4=", Instant.EPOCH))
                .hasMessageContaining("PNG, JPG o WebP");
        assertThatThrownBy(() -> Workspace.create("acme", "Acme", null, null, null, "data:image/png;base64," + "A".repeat(Workspace.MAX_LOGO), Instant.EPOCH))
                .hasMessageContaining("150 KB");
    }

    @Test
    void slugComesFromTheCompanyName() {
        assertThat(Workspace.slugOf("Clínica Andes & Cía.")).isEqualTo("clinica-andes-cia");
        assertThat(Workspace.slugOf("!")).isEqualTo("espacio");
    }
}
