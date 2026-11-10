package ec.kuador.board.catalog.domain;

import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.McpServer;
import ec.kuador.board.catalog.Methodology;
import ec.kuador.board.catalog.WorkflowTemplate;
import java.util.Arrays;
import java.util.Optional;

/** Los cuatro catálogos: qué registro los representa y cómo se nombran en la API. */
public enum CatalogType {
    WORKFLOW_TEMPLATE(WorkflowTemplate.class, "workflow-templates"),
    METHODOLOGY(Methodology.class, "methodologies"),
    AGENT_ROLE(AgentRole.class, "roles"),
    MCP_SERVER(McpServer.class, "mcp-servers");

    private final Class<?> recordType;
    private final String path;

    CatalogType(Class<?> recordType, String path) {
        this.recordType = recordType;
        this.path = path;
    }

    public Class<?> recordType() {
        return recordType;
    }

    public String path() {
        return path;
    }

    public static Optional<CatalogType> ofPath(String path) {
        return Arrays.stream(values()).filter(t -> t.path.equals(path)).findFirst();
    }
}
