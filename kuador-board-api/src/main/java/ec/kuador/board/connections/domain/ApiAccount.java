package ec.kuador.board.connections.domain;

import ec.kuador.board.shared.error.DomainException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Un sistema que se conecta por su API (descrita con OpenAPI o Swagger): la dirección base, cómo viaja la credencial y
 * las operaciones que el agente puede usar. Se guarda entero, cifrado, como credencial de la conexión.
 * {@code header}: la cabecera de la credencial (Authorization, X-API-Key…); {@code prefix}: lo que va antes del valor
 * («Bearer », o vacío); {@code credential}: el valor (puede faltar si la API no pide).
 */
public record ApiAccount(String baseUrl, String header, String prefix, String credential, List<Operation> operations) {

    /** Así empieza la dirección de una conexión por API: «api+https://erp.empresa.com/api». */
    public static final String SCHEME = "api+";
    public static final int MAX_OPERATIONS = 40;
    private static final Pattern HEADER = Pattern.compile("^[A-Za-z][A-Za-z0-9-]{0,63}$");
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,59}$");

    /** Un parámetro de la operación: dónde va (path, query o header). */
    public record Param(String name, String in, boolean required) {}

    /**
     * Una operación de la API que el agente puede usar. {@code name}: el nombre de la herramienta; {@code schema}: el
     * JSON Schema de sus argumentos (los parámetros y, si lleva, «body»); {@code readOnly}: GET, HEAD (solo consulta).
     */
    public record Operation(String name, String method, String path, String description, List<Param> params, boolean body, Map<String, Object> schema,
            boolean readOnly) {
        public Operation {
            if (name == null || !NAME.matcher(name).matches()) throw new DomainException("Nombre de operación no válido: " + name + ".");
            if (path == null || !path.startsWith("/")) throw new DomainException("La operación " + name + " no tiene una ruta válida.");
            params = params == null ? List.of() : List.copyOf(params);
            schema = schema == null ? Map.of("type", "object", "properties", Map.of()) : schema;
        }
    }

    public ApiAccount {
        if (baseUrl == null || !(baseUrl.strip().startsWith("https://") || baseUrl.strip().startsWith("http://"))) {
            throw new DomainException("Indica la dirección base de la API (https://…).");
        }
        baseUrl = baseUrl.strip().replaceAll("/+$", "");
        header = header == null || header.isBlank() ? "Authorization" : header.strip();
        if (!HEADER.matcher(header).matches()) throw new DomainException("Nombre de cabecera no válido: " + header + ".");
        prefix = prefix == null ? "" : prefix;
        if (operations == null || operations.isEmpty()) throw new DomainException("Elige al menos una operación que el agente pueda usar.");
        if (operations.size() > MAX_OPERATIONS) throw new DomainException("Elige hasta " + MAX_OPERATIONS + " operaciones: las que el agente de verdad necesita.");
        operations = List.copyOf(operations);
    }

    /** La dirección que se guarda en la conexión y por la que el tablero sabe que es una API. */
    public String url() {
        return SCHEME + baseUrl;
    }

    public ApiAccount withCredential(String other) {
        return new ApiAccount(baseUrl, header, prefix, other, operations);
    }
}
