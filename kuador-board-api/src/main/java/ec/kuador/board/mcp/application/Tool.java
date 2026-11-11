package ec.kuador.board.mcp.application;

import java.util.List;
import java.util.Map;

/**
 * Una herramienta MCP: nombre, descripción para el modelo y esquema JSON de sus argumentos.
 *
 * @param readOnly no cambia nada en el tablero (se anuncia como {@code readOnlyHint})
 */
public record Tool(String name, String title, String description, Map<String, Object> inputSchema, boolean readOnly) {

    /** Esquema de objeto con sus propiedades de texto; {@code required} son obligatorias. */
    static Map<String, Object> schema(Map<String, String> properties, List<String> required) {
        return schema(properties, Map.of(), required);
    }

    /** Como {@link #schema(Map, List)}, más propiedades con su propio esquema (p. ej. una lista de objetos). */
    static Map<String, Object> schema(Map<String, String> properties, Map<String, Map<String, Object>> structured, List<String> required) {
        Map<String, Object> props = new java.util.TreeMap<>();
        properties.forEach((name, description) -> props.put(name, Map.of("type", "string", "description", description)));
        props.putAll(structured);
        return Map.of("type", "object", "properties", props, "required", required, "additionalProperties", false);
    }
}
