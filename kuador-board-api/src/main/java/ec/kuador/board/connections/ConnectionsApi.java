package ec.kuador.board.connections;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lo que otros módulos (los agentes) usan de las conexiones del negocio. */
public interface ConnectionsApi {

    /**
     * Una herramienta de una conexión que un rol puede usar en un tablero. {@code name}: el nombre con el que la ve el
     * modelo (conexión y herramienta, único en el tablero). {@code schema}: JSON Schema de sus argumentos.
     */
    record Tool(String name, String connection, String connectionName, String tool, String description, Map<String, Object> schema, boolean readOnly) {}

    /** Las herramientas que el rol puede usar en el tablero: las que solo leen y las de escritura habilitadas. */
    List<Tool> toolsFor(String projectKey, String roleKey);

    /**
     * Llama a una herramienta en nombre de un agente y devuelve el texto de su respuesta. Vuelve a comprobar que el rol
     * la puede usar en ese tablero. {@code issueKey}: la tarjeta que se trabaja, para el registro.
     */
    String call(String projectKey, String roleKey, String toolName, Map<String, Object> arguments, UUID actorId, String issueKey);
}
