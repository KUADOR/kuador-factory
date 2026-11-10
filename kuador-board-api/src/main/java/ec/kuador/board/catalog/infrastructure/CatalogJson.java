package ec.kuador.board.catalog.infrastructure;

import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Conversión entre las entradas del catálogo y su JSON. Lo que es columna (id, builtIn, version) no
 * se guarda en el JSON. Al leer de la base se toleran campos desconocidos (el formato puede
 * evolucionar); al recibir de la API no, para detectar errores de tipeo.
 */
public final class CatalogJson {

    static final JsonMapper STORAGE = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
    public static final JsonMapper STRICT = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
    private static final List<String> COLUMNS = List.of("id", "builtIn", "version");

    private CatalogJson() {}

    public static String payload(Object entry) {
        ObjectNode node = STORAGE.valueToTree(entry);
        node.remove(COLUMNS);
        return node.toString();
    }

    public static <T> T read(String payload, java.util.UUID id, boolean builtIn, long version, Class<T> type) {
        ObjectNode node = (ObjectNode) STORAGE.readTree(payload);
        node.put("id", id.toString()).put("builtIn", builtIn).put("version", version);
        return STORAGE.treeToValue(node, type);
    }

    public static <T> T fromRequest(JsonNode body, Class<T> type) {
        ObjectNode node = body.isObject() ? ((ObjectNode) body).deepCopy() : STRICT.createObjectNode();
        // Lo que manda la interfaz al editar (id, builtIn) no lo decide el cliente; la versión sí se usa.
        node.remove(List.of("id", "builtIn"));
        if (!node.has("version")) node.put("version", 0);
        return STRICT.treeToValue(node, type);
    }
}
