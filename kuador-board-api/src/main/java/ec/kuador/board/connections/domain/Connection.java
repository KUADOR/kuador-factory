package ec.kuador.board.connections.domain;

import ec.kuador.board.shared.error.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Un sistema de la empresa conectado por MCP. {@code secret}: la credencial cifrada (o nula si el servidor no pide);
 * {@code header}: en qué cabecera viaja (nulo = {@code Authorization: Bearer …}). {@code tools}: lo que el servidor
 * ofreció la última vez que se comprobó. {@code allowedWrites}: herramientas que escriben y que quien administra
 * habilitó para los agentes; las demás que escriben no se ofrecen. {@code oauth}: si se entra iniciando sesión (OAuth)
 * en vez de con una clave, los datos de esa sesión cifrados (cliente, tokens); nulo en las que usan clave.
 */
public record Connection(String key, String name, String url, String header, String secret, List<ToolDef> tools, Set<String> allowedWrites,
        Instant checkedAt, String oauth) {

    public static final Pattern KEY = Pattern.compile("^[A-Z][A-Z0-9_]{1,23}$");
    private static final Pattern HEADER = Pattern.compile("^[A-Za-z][A-Za-z0-9-]{0,63}$");
    /** Cabeceras que decide el tablero, no quien configura. */
    private static final Set<String> RESERVED = Set.of("host", "content-type", "content-length", "accept", "mcp-session-id", "mcp-protocol-version", "cookie");

    /** Una herramienta del servidor. {@code readOnly}: el servidor declara que solo lee (no cambia nada afuera). */
    public record ToolDef(String name, String description, Map<String, Object> schema, boolean readOnly) {}

    public Connection {
        if (key == null || !KEY.matcher(key).matches()) throw new DomainException("La clave va en mayúsculas, de 2 a 24 caracteres (ej. CORREO o AGENDA).");
        if (name == null || name.isBlank() || name.length() > 80) throw new DomainException("El nombre es obligatorio y tiene hasta 80 caracteres.");
        name = name.strip();
        if (url == null || url.isBlank() || url.length() > 500) throw new DomainException("Indica la dirección del servidor (hasta 500 caracteres).");
        url = url.strip();
        header = header == null || header.isBlank() ? null : header.strip();
        if (header != null && (!HEADER.matcher(header).matches() || RESERVED.contains(header.toLowerCase(java.util.Locale.ROOT)))) {
            throw new DomainException("Nombre de cabecera no válido: " + header + ".");
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
        allowedWrites = allowedWrites == null ? Set.of() : Set.copyOf(allowedWrites);
    }

    /** Si los agentes pueden usar esta herramienta: las que solo leen siempre; las que escriben, si se habilitaron. */
    public boolean offers(ToolDef tool) {
        return tool.readOnly() || allowedWrites.contains(tool.name());
    }

    public Connection withTools(List<ToolDef> fresh, Instant now) {
        // Una herramienta habilitada que ya no existe deja de estarlo.
        Set<String> names = fresh.stream().map(ToolDef::name).collect(java.util.stream.Collectors.toSet());
        Set<String> kept = allowedWrites.stream().filter(names::contains).collect(java.util.stream.Collectors.toSet());
        return new Connection(key, name, url, header, secret, fresh, kept, now, oauth);
    }

    public Connection withAllowedWrites(Set<String> writes) {
        Set<String> writable = tools.stream().filter(t -> !t.readOnly()).map(ToolDef::name).collect(java.util.stream.Collectors.toSet());
        for (String w : writes) if (!writable.contains(w)) throw new DomainException("«" + w + "» no es una herramienta que escriba en esta conexión.");
        return new Connection(key, name, url, header, secret, tools, writes, checkedAt, oauth);
    }

    /** La misma conexión con otra sesión OAuth (cifrada). */
    public Connection withOauth(String session) {
        return new Connection(key, name, url, header, secret, tools, allowedWrites, checkedAt, session);
    }
}
