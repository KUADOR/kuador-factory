package ec.kuador.board.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.testing.InMemoryCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

class CapabilityTest {

    @Test
    void whatWasSavedBeforeCapabilitiesKeepsWorking() {
        // Software lo tenía todo; los demás tableros ganan documentos y nada más cambia.
        assertThat(Capability.defaultsFor(ProjectKind.SOFTWARE)).containsExactlyInAnyOrder(Capability.DOCUMENTS, Capability.REPOSITORY, Capability.SPLIT);
        assertThat(Capability.defaultsFor(ProjectKind.GENERAL)).containsExactly(Capability.DOCUMENTS);
        assertThat(InMemoryCatalog.factoryTemplate("SOFTWARE_FACTORY").can(Capability.REPOSITORY)).isTrue();
        assertThat(InMemoryCatalog.factoryTemplate("SALES").can(Capability.REPOSITORY)).isFalse();
        assertThat(InMemoryCatalog.factoryTemplate("SALES").can(Capability.DOCUMENTS)).isTrue();
    }

    @Test
    void aTemplateDeclaresWhatItUsesWhateverItsKind() {
        WorkflowTemplate sales = InMemoryCatalog.factoryTemplate("SALES");
        WorkflowTemplate clinic = new WorkflowTemplate(null, "CLINIC", "Clínica", null, null, sales.columns(), sales.issueTypes(), List.of(), List.of(),
                List.of(), List.of(Capability.SPLIT, Capability.DOCUMENTS, Capability.SPLIT), false, 0);
        assertThat(clinic.capabilities()).containsExactly(Capability.DOCUMENTS, Capability.SPLIT);
        assertThat(clinic.kind()).isEqualTo(ProjectKind.GENERAL);
    }

    @Test
    void aRepositoryAlwaysCarriesDocuments() {
        assertThat(Capability.normalize(List.of(Capability.REPOSITORY))).containsExactly(Capability.DOCUMENTS, Capability.REPOSITORY);
        assertThat(Capability.kindOf(List.of(Capability.REPOSITORY))).isEqualTo(ProjectKind.SOFTWARE);
    }
}
