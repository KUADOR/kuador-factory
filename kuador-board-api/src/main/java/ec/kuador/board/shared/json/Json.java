package ec.kuador.board.shared.json;

import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** JSON para valores guardados en columnas de texto (listas pequeñas como los tipos de issue). */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private Json() {}

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /** Un objeto JSON de textos ({@code {"clave":"valor"}}), en su orden. */
    public static java.util.Map<String, String> readMap(String json) {
        if (json == null || json.isBlank()) return java.util.Map.of();
        return MAPPER.readValue(json, MAPPER.getTypeFactory().constructMapType(java.util.LinkedHashMap.class, String.class, String.class));
    }

    public static <T> List<T> readList(String json, Class<T> elementType) {
        if (json == null || json.isBlank()) return List.of();
        return MAPPER.readValue(json, MAPPER.getTypeFactory().constructCollectionType(List.class, elementType));
    }
}
