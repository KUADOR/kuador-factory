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

    public static <T> List<T> readList(String json, Class<T> elementType) {
        if (json == null || json.isBlank()) return List.of();
        return MAPPER.readValue(json, MAPPER.getTypeFactory().constructCollectionType(List.class, elementType));
    }
}
