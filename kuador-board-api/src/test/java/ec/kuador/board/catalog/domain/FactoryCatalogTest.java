package ec.kuador.board.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.DocumentDef;
import ec.kuador.board.catalog.Methodology;
import ec.kuador.board.docs.domain.DocumentTemplate;
import ec.kuador.board.testing.InMemoryCatalog;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** El catálogo de fábrica carga, pasa sus propias reglas y trae lo que cada espacio necesita. */
class FactoryCatalogTest {

    private final FactoryCatalog factory = InMemoryCatalog.FACTORY;

    @Test
    void bringsTheExpectedDefaults() {
        assertThat(factory.workflowTemplates()).extracting(t -> t.key()).containsExactly("SOFTWARE", "SOFTWARE_FACTORY", "SALES", "TASKS");
        assertThat(factory.methodologies()).extracting(Methodology::key).containsExactly("SDD", "ODD", "FREE");
        assertThat(factory.roles()).extracting(AgentRole::key)
                .contains("PRODUCT_OWNER", "ARCHITECT", "BACKEND", "FRONTEND", "QA", "DEVOPS", "RELEASE", "MARKETING");
        assertThat(factory.mcpServers()).extracting(s -> s.key()).contains("CONTEXT7", "GITHUB", "GITHUB_LOCAL");
        assertThat(factory.version()).isGreaterThanOrEqualTo(2);
        assertThat(factory.addedAfter(1)).extracting(e -> e.getValue()).contains("GITHUB", "GITHUB_LOCAL");
        assertThat(factory.addedAfter(factory.version())).isEmpty();
        assertThat(factory.agentSeeds()).hasSize(factory.roles().size());
    }

    @Test
    void onlyThePoAutomatesForNowAndTheMethodologiesAssignItsDocuments() {
        assertThat(factory.roles()).filteredOn(r -> r.automation() != AgentRole.Automation.NONE)
                .extracting(AgentRole::key).containsExactly("PRODUCT_OWNER");
        Methodology sdd = InMemoryCatalog.factoryMethodology("SDD");
        assertThat(sdd.document("SPEC")).get().extracting(DocumentDef::writtenBy, DocumentDef::condition)
                .containsExactly("PRODUCT_OWNER", DocumentDef.Condition.ALWAYS);
        assertThat(sdd.document("CONSTITUTION")).get().extracting(DocumentDef::pathPattern).isEqualTo(".specify/memory/constitution.md");
        assertThat(InMemoryCatalog.factoryMethodology("ODD").document("TRACKING")).get().extracting(DocumentDef::condition)
                .isEqualTo(DocumentDef.Condition.SUBSTANTIAL);
    }

    @Test
    void theFactoryTemplateHasRolesGatesAndASteppedFlow() {
        var t = InMemoryCatalog.factoryTemplate("SOFTWARE_FACTORY");
        assertThat(t.stages()).filteredOn(s -> s.gate()).extracting(s -> s.column()).containsExactly("APPROVAL", "PR_REVIEW", "RELEASE");
        assertThat(t.stages()).filteredOn(s -> s.column().equals("DEVELOPMENT")).singleElement()
                .satisfies(s -> assertThat(s.roles()).containsExactly("BACKEND", "FRONTEND"));
        var roles = factory.roles().stream().map(AgentRole::key).toList();
        assertThat(t.stages()).allSatisfy(s -> assertThat(roles).containsAll(s.roles()));
        assertThat(t.transitions()).isNotEmpty();
        assertThat(factory.addedAfter(2)).filteredOn(e -> e.getKey() == CatalogType.WORKFLOW_TEMPLATE)
                .extracting(e -> e.getValue()).containsExactly("SOFTWARE_FACTORY");
    }

    @Test
    void piecesCoverBackendFrontendAndMicrofrontendsWithoutHardcodedRepos() {
        assertThat(factory.componentTemplates()).extracting(c -> c.kind().name())
                .contains("BACKEND", "FRONTEND", "MICROFRONTEND", "SHELL");
        assertThat(factory.componentTemplates()).as("cada empresa apunta sus propias plantillas").allMatch(c -> c.templateRepo() == null);
        assertThat(factory.componentTemplates()).allSatisfy(c -> assertThat(c.commands().test()).isNotBlank());
        assertThat(factory.addedAfter(3)).extracting(e -> e.getKey().name()).contains("COMPONENT_TEMPLATE");
        // La versión 5 suma el rol Analista (informes a partir de planillas) a los espacios existentes.
        assertThat(factory.addedAfter(4)).extracting(e -> e.getKey().name() + ":" + e.getValue()).containsExactly("AGENT_ROLE:ANALYST", "AGENT_POLICY:DEFAULT");
        // La versión 6 suma las reglas de agentes editables.
        assertThat(factory.addedAfter(5)).extracting(e -> e.getKey().name() + ":" + e.getValue()).containsExactly("AGENT_POLICY:DEFAULT");
    }

    @Test
    void everyTemplateRendersWithoutLeftoverPlaceholders() {
        for (Methodology m : factory.methodologies()) {
            for (DocumentDef d : m.documents()) {
                assertThat(DocumentTemplate.render(d.template(), "Kuador", "KUA-1", "Algo", LocalDate.of(2026, 11, 10)))
                        .as(m.key() + "/" + d.kind()).startsWith("# ").doesNotContain("{project}", "{key}", "{title}", "{date}");
            }
        }
    }
}
