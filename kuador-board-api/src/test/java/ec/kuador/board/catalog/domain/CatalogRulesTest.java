package ec.kuador.board.catalog.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.catalog.ColumnCategory;
import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.DocumentDef;
import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.catalog.IssueTypeDef;
import ec.kuador.board.catalog.McpServer;
import ec.kuador.board.catalog.Methodology;
import ec.kuador.board.catalog.ProjectKind;
import ec.kuador.board.catalog.WorkflowTemplate;
import ec.kuador.board.shared.error.DomainException;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogRulesTest {

    private static WorkflowTemplate template(List<ColumnDef> columns, List<IssueTypeDef> types) {
        return new WorkflowTemplate(null, "KANBAN", "Kanban", null, ProjectKind.GENERAL, columns, types, List.of(), List.of(), false, 0);
    }

    private static DocumentDef doc(String kind, DocumentDef.Scope scope, String path) {
        return new DocumentDef(kind, "Doc " + kind, scope, false, "para", path, "# Doc", null, DocumentDef.Condition.ALWAYS, null);
    }

    private static Methodology methodology(DocumentDef... docs) {
        return new Methodology(null, "KANBAN_DOCS", "Kanban docs", "Resumen", List.of("regla"), List.of(docs), false, 0);
    }

    @Test
    void acceptsAQuarkusStyleCustomMethodologyAndTemplate() {
        var columns = List.of(new ColumnDef("TODO", "Por hacer", ColumnCategory.TODO), new ColumnDef("DONE", "Hecho", ColumnCategory.DONE));
        assertThatCode(() -> CatalogRules.validate(CatalogType.WORKFLOW_TEMPLATE, "KANBAN",
                template(columns, List.of(new IssueTypeDef(IssueType.TASK, "Tarjeta"))))).doesNotThrowAnyException();
        assertThatCode(() -> CatalogRules.validate(CatalogType.METHODOLOGY, "KANBAN_DOCS", methodology(
                doc("ADR", DocumentDef.Scope.ISSUE, "docs/adr/{task}.md"), doc("README", DocumentDef.Scope.PROJECT, "README.md"))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidEntries() {
        var one = List.of(new ColumnDef("TODO", "Por hacer", ColumnCategory.TODO));
        assertThatThrownBy(() -> CatalogRules.validate(CatalogType.WORKFLOW_TEMPLATE, "kanban", template(one, List.of(new IssueTypeDef(IssueType.TASK, "T")))))
                .isInstanceOf(DomainException.class).hasMessageContaining("Clave no válida");
        assertThatThrownBy(() -> CatalogRules.validate(CatalogType.WORKFLOW_TEMPLATE, "KANBAN", template(one, List.of(new IssueTypeDef(IssueType.EPIC, "Épica")))))
                .hasMessageContaining("además de las épicas");
        assertThatThrownBy(() -> CatalogRules.validate(CatalogType.METHODOLOGY, "KANBAN_DOCS", methodology(doc("ADR", DocumentDef.Scope.ISSUE, "docs/adr.md"))))
                .hasMessageContaining("{feature} o {task}");
        assertThatThrownBy(() -> CatalogRules.validate(CatalogType.METHODOLOGY, "KANBAN_DOCS", methodology(doc("NOTES", DocumentDef.Scope.PROJECT, "../etc/x.md"))))
                .hasMessageContaining("Ruta no válida");
        assertThatThrownBy(() -> CatalogRules.validate(CatalogType.MCP_SERVER, "LOCAL_DB", new McpServer(null, "LOCAL_DB", "BD", "desc",
                McpServer.Transport.STDIO, " ", List.of(), null, List.of(), null, false, 0))).hasMessageContaining("comando");
        assertThatThrownBy(() -> CatalogRules.validate(CatalogType.MCP_SERVER, "LOCAL_DB", new McpServer(null, "LOCAL_DB", "BD", "desc",
                McpServer.Transport.STDIO, "npx", List.of(), null, List.of(new McpServer.EnvVar("api-key", true, null)), null, false, 0)))
                .hasMessageContaining("Nombre de variable");
    }
}
