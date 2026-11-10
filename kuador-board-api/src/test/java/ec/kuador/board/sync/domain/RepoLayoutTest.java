package ec.kuador.board.sync.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import ec.kuador.board.testing.InMemoryCatalog;
import org.junit.jupiter.api.Test;

class RepoLayoutTest {

    /** SDD y ODD juntas: cubre rutas fijas, carpetas por feature y archivos por tarea. */
    private static final RepoLayout LAYOUT = RepoLayout.of(java.util.stream.Stream.of("SDD", "ODD")
            .flatMap(k -> InMemoryCatalog.factoryMethodology(k).documents().stream()).toList());

    @Test
    void recognizesMethodologyConventions() {
        assertThat(LAYOUT.classify(".specify/memory/constitution.md")).contains(new RepoLayout.Match("CONSTITUTION", ".specify/memory/constitution.md", false));
        assertThat(LAYOUT.classify("specs/001-lead-capture/plan.md")).contains(new RepoLayout.Match("PLAN", "specs/001-lead-capture", true));
        assertThat(LAYOUT.classify("odd/tasks/password-recovery.md")).contains(new RepoLayout.Match("TRACKING", "odd/tasks/password-recovery.md", true));
        assertThat(LAYOUT.classify("specs/001-lead-capture/research.md")).isEmpty();
        assertThat(LAYOUT.classify("README.md")).isEmpty();
    }

    @Test
    void suggestsIssueTitleFromHeadingOrFolder() {
        assertThat(RepoLayout.suggestTitle("specs/001-lead-capture", Optional.of("# Feature Specification: Captura de leads\n\nx")))
                .isEqualTo("Captura de leads");
        assertThat(RepoLayout.suggestTitle("specs/001-lead-capture",
                Optional.of("# Especificación de funcionalidad: Captación de leads (formulario y chat)")))
                .isEqualTo("Captación de leads (formulario y chat)");
        assertThat(RepoLayout.suggestTitle("specs/001-lead-capture", Optional.of("# Feature Specification: [FEATURE NAME]")))
                .isEqualTo("Lead capture");
        assertThat(RepoLayout.suggestTitle("odd/tasks/password-recovery.md", Optional.empty())).isEqualTo("Password recovery");
    }
}
