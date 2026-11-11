package ec.kuador.board.catalog;

import java.util.List;
import java.util.Optional;

/** API pública del catálogo del espacio de trabajo actual. */
public interface CatalogApi {

    List<WorkflowTemplate> workflowTemplates();

    Optional<WorkflowTemplate> workflowTemplate(String key);

    List<Methodology> methodologies();

    Optional<Methodology> methodology(String key);

    List<AgentRole> roles();

    Optional<AgentRole> role(String key);

    List<McpServer> mcpServers();

    /** Plantillas de pieza (API, web, microservicio, microfrontend...) con que se arman los proyectos. */
    List<ComponentTemplate> componentTemplates();

    Optional<ComponentTemplate> componentTemplate(String key);

    /** Datos de fábrica para sembrar un espacio nuevo, incluidos los agentes que nacen con él. */
    record AgentSeed(String roleKey, String memberName, String color) {}

    /** Copia el catálogo de fábrica al espacio actual y devuelve los agentes a crear. */
    List<AgentSeed> provisionDefaults();

    /** Versión actual del catálogo de fábrica. Un espacio recién creado ya la tiene. */
    int factoryVersion();

    /**
     * Agrega al espacio actual lo que el catálogo de fábrica sumó después de {@code fromVersion}. No
     * toca lo existente: si el espacio ya tiene una entrada con esa clave, se respeta la suya.
     * Devuelve cuántas entradas agregó.
     */
    int receiveFactoryAdditions(int fromVersion);
}
