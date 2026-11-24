package ec.kuador.board.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.catalog.infrastructure.defaults.YamlFactoryCatalog;
import org.junit.jupiter.api.Test;

/** Los documentos que trae el tablero de software aunque la metodología no los pida. */
class SoftwareDocumentsTest {

    private final YamlFactoryCatalog factory = new YamlFactoryCatalog();

    private Methodology methodology(String key) {
        return factory.methodologies().stream().filter(m -> m.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void inOddTheArchitectAndUiUxGetTheirOwnCardDocuments() {
        Methodology odd = methodology("ODD");

        assertThat(odd.issueDocumentOf("ARCHITECT")).map(DocumentDef::kind).hasValue(SoftwareDocuments.SOLUTION_KIND);
        assertThat(odd.issueDocumentOf("UX_UI")).map(DocumentDef::kind).hasValue(SoftwareDocuments.SCREENS_KIND);
        assertThat(odd.document(SoftwareDocuments.IDENTITY_KIND)).map(DocumentDef::pathPattern).hasValue("DESIGN.md");
    }

    @Test
    void inSddTheArchitectKeepsThePlanWithItsDiagramSection() {
        Methodology sdd = methodology("SDD");

        assertThat(sdd.issueDocumentOf("ARCHITECT")).map(DocumentDef::kind).hasValue("PLAN");
        assertThat(sdd.document("PLAN").orElseThrow().template()).contains("## Diagrama de la solución");
        assertThat(sdd.documents(DocumentDef.Scope.ISSUE)).extracting(DocumentDef::kind).doesNotContain(SoftwareDocuments.SOLUTION_KIND);
    }

    @Test
    void theIdentityIsNotABaseDocument() {
        assertThat(methodology("SDD").baseDocuments()).extracting(DocumentDef::kind)
                .doesNotContain(SoftwareDocuments.IDENTITY_KIND, Methodology.DEPLOYMENT_KIND);
    }

    @Test
    void theFactoryTemplateBringsDevopsUiUxAndTheReviewerAndDesignAsksForTheDiagram() {
        WorkflowTemplate t = factory.workflowTemplates().stream().filter(x -> x.key().equals("SOFTWARE_FACTORY")).findFirst().orElseThrow();

        assertThat(t.teamRoles()).contains("DEVOPS", "UX_UI", "PR_REVIEWER", "ARCHITECT");
        StageDef design = t.stages().stream().filter(s -> s.column().equals("DESIGN")).findFirst().orElseThrow();
        assertThat(design.epicFirst()).containsExactly("UX_UI");
        assertThat(design.requires()).extracting(Requirement::kind).containsExactly(Requirement.Kind.DIAGRAM);
    }

    @Test
    void codersAndTheReviewerGetTheCodeCriteria() {
        AgentPolicy policy = factory.agentPolicies().getFirst();

        assertThat(policy.rules().code()).contains("Context7", "SOLID", "patrón");
        assertThat(factory.roles().stream().filter(r -> r.key().equals("UX_UI")).findFirst().orElseThrow().worksOnCode())
                .as("UI/UX trabaja en el tablero").isFalse();
    }
}
