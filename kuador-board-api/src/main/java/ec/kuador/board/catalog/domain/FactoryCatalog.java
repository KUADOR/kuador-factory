package ec.kuador.board.catalog.domain;

import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.McpServer;
import ec.kuador.board.catalog.Methodology;
import ec.kuador.board.catalog.WorkflowTemplate;
import java.util.List;
import java.util.Optional;

/** Puerto: los valores de fábrica (se copian a cada espacio nuevo y sirven para restaurar). */
public interface FactoryCatalog {

    List<WorkflowTemplate> workflowTemplates();

    List<Methodology> methodologies();

    List<AgentRole> roles();

    List<McpServer> mcpServers();

    List<CatalogApi.AgentSeed> agentSeeds();

    /** Versión del catálogo de fábrica (crece cuando se agregan entradas). */
    int version();

    /** Entradas (tipo y clave) agregadas en versiones posteriores a {@code version}. */
    List<java.util.Map.Entry<CatalogType, String>> addedAfter(int version);

    default Optional<Object> entry(CatalogType type, String key) {
        List<?> all = switch (type) {
            case WORKFLOW_TEMPLATE -> workflowTemplates();
            case METHODOLOGY -> methodologies();
            case AGENT_ROLE -> roles();
            case MCP_SERVER -> mcpServers();
        };
        return all.stream().filter(e -> key.equals(CatalogKeys.of(e))).map(e -> (Object) e).findFirst();
    }
}
