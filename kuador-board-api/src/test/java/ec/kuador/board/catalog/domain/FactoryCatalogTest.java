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
        assertThat(factory.workflowTemplates()).extracting(t -> t.key()).containsExactly("SOFTWARE", "SOFTWARE_FACTORY", "SALES", "MARKETING", "TASKS");
        assertThat(factory.methodologies()).extracting(Methodology::key).containsExactly("SDD", "ODD", "FREE", "MANUAL");
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
                .extracting(e -> e.getValue()).containsExactly("SOFTWARE_FACTORY", "MARKETING");
    }

    @Test
    void piecesCoverBackendFrontendAndMicrofrontendsWithoutHardcodedRepos() {
        assertThat(factory.componentTemplates()).extracting(c -> c.kind().name())
                .contains("BACKEND", "FRONTEND", "MICROFRONTEND", "SHELL");
        assertThat(factory.componentTemplates()).as("cada empresa apunta sus propias plantillas").allMatch(c -> c.templateRepo() == null);
        assertThat(factory.componentTemplates()).allSatisfy(c -> assertThat(c.commands().test()).isNotBlank());
        assertThat(factory.addedAfter(3)).extracting(e -> e.getKey().name()).contains("COMPONENT_TEMPLATE");
        // La versión 5 suma el rol Analista (informes a partir de planillas) a los espacios existentes.
        assertThat(factory.addedAfter(4)).extracting(e -> e.getKey().name() + ":" + e.getValue()).startsWith("AGENT_ROLE:ANALYST", "AGENT_POLICY:DEFAULT");
        // La versión 6 suma las reglas de agentes editables; la 7, la API Spring Boot 3 (Java 21) y los monolitos.
        assertThat(factory.addedAfter(5)).extracting(e -> e.getKey().name() + ":" + e.getValue()).first().isEqualTo("AGENT_POLICY:DEFAULT");
        assertThat(factory.addedAfter(6)).extracting(e -> e.getValue()).contains("API_SPRING_3", "MONOLITH_SPRING", "MONOLITH_LARAVEL", "MONOLITH_NEXTJS");
        assertThat(factory.componentTemplates()).filteredOn(c -> c.kind() == ec.kuador.board.catalog.ComponentTemplate.Kind.MONOLITH).hasSize(6);
        // La versión 9 suma la app móvil Flutter: el job «ci» lo exige la rama protegida y lo trae el CI del tablero.
        assertThat(factory.addedAfter(8)).extracting(e -> e.getKey().name() + ":" + e.getValue()).first().isEqualTo("COMPONENT_TEMPLATE:MOBILE_FLUTTER");
        // La versión 10 suma la plantilla Marketing y el agente de Diseño que hace sus imágenes.
        assertThat(factory.addedAfter(9)).extracting(e -> e.getKey().name() + ":" + e.getValue())
                .containsExactly("WORKFLOW_TEMPLATE:MARKETING", "AGENT_ROLE:DESIGNER", "AGENT_ROLE:REVIEWER", "METHODOLOGY:MANUAL");
        // La versión 11 suma lo que usa un tablero que no es de software: un revisor de cualquier trabajo y un manual de procedimientos.
        assertThat(factory.addedAfter(10)).extracting(e -> e.getKey().name() + ":" + e.getValue()).containsExactly("AGENT_ROLE:REVIEWER", "METHODOLOGY:MANUAL");
        assertThat(factory.componentTemplates()).filteredOn(c -> c.key().equals("MOBILE_FLUTTER")).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(ec.kuador.board.catalog.ComponentTemplate.Kind.MOBILE);
            assertThat(c.commands().test()).isEqualTo("flutter analyze && flutter test");
            assertThat(c.ciChecks()).containsExactly("ci");
        });
    }

    @Test
    void everyMethodologyHasAnOptionalLivingDeploymentDocument() {
        for (Methodology m : factory.methodologies()) {
            assertThat(m.documents(DocumentDef.Scope.PROJECT)).as(m.key()).extracting(DocumentDef::kind).contains(Methodology.DEPLOYMENT_KIND);
            assertThat(m.document(Methodology.DEPLOYMENT_KIND)).contains(Methodology.DEPLOYMENT);
            assertThat(m.baseDocuments()).as("no es obligatorio antes del repo").extracting(DocumentDef::kind).doesNotContain(Methodology.DEPLOYMENT_KIND);
        }
        String rendered = DocumentTemplate.render(Methodology.DEPLOYMENT.template(), "Clínica", null, null, LocalDate.of(2026, 11, 19));
        assertThat(rendered).startsWith("# Despliegue de Clínica").contains("| Pruebas | develop |", "| Producción | main |", "| 2026-11-19 | Primera versión |")
                .doesNotContain("{project}", "{date}");
        assertThat(Methodology.DEPLOYMENT.agentInstructions()).contains("docs/deployment/changes/", "Nunca escribas valores de secretos");
        // Cada PR deja su nota en un archivo propio: sumar filas a la misma tabla hacía chocar a los PR en paralelo.
        assertThat(ec.kuador.board.catalog.AgentRules.softwareProject(true)).anySatisfy(r -> assertThat(r)
                .contains("docs/deployment/changes/<clave de la tarjeta>.md", "no edites «Despliegue»", "Si preparas el release"));
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
