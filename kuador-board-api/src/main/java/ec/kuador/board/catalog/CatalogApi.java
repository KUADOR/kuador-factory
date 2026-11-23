package ec.kuador.board.catalog;

import java.util.List;
import java.util.Optional;

/** API pública del catálogo del espacio de trabajo actual. */
public interface CatalogApi {

    List<WorkflowTemplate> workflowTemplates();

    Optional<WorkflowTemplate> workflowTemplate(String key);

    /** Guarda una plantilla de flujo propia del espacio (se valida como cualquier otra) y devuelve la guardada. */
    default WorkflowTemplate saveWorkflowTemplate(WorkflowTemplate template) {
        throw new UnsupportedOperationException("saveWorkflowTemplate");
    }

    List<Methodology> methodologies();

    Optional<Methodology> methodology(String key);

    List<AgentRole> roles();

    Optional<AgentRole> role(String key);

    List<McpServer> mcpServers();

    /** Plantillas de pieza (API, web, microservicio, microfrontend...) con que se arman los proyectos. */
    List<ComponentTemplate> componentTemplates();

    Optional<ComponentTemplate> componentTemplate(String key);

    /**
     * Las reglas de agentes vigentes del espacio: la entrada del espacio con lo vacío tomado de fábrica (o la de fábrica
     * si el espacio todavía no la tiene). Siempre devuelve una.
     */
    AgentPolicy agentPolicy();

    /** Datos de fábrica para sembrar un espacio nuevo, incluidos los agentes que nacen con él. */
    record AgentSeed(String roleKey, String memberName, String color) {}

    /**
     * Roles del equipo de agentes por defecto de una plantilla: los de la copia del espacio o, si no declara ninguno
     * (copias anteriores a los equipos), los de la plantilla publicada en la plataforma.
     */
    java.util.List<String> defaultTeam(String templateKey);

    /** Nombre y color del agente que nace con un rol (del catálogo de la plataforma). */
    java.util.Optional<AgentSeed> agentSeed(String roleKey);

    /** El tablero con el que arranca un espacio según su paquete. */
    record StarterBoard(String key, String name, String templateKey) {}

    /**
     * Guarda el paquete por industria que eligió el espacio actual y devuelve su tablero inicial, si tiene. Un código
     * desconocido o inactivo se ignora.
     */
    java.util.Optional<StarterBoard> choosePackage(String code);

    /**
     * Aplica las entradas actualizadas de una versión publicada del catálogo de la plataforma al espacio actual: solo
     * las de fábrica que el equipo no modificó (su contenido es igual al publicado antes). Devuelve cuántas cambió.
     */
    int applyFactoryUpdates(java.util.List<FactoryCatalogPublished.Update> updates);

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
