package ec.kuador.board.connections.infrastructure.mcp;

import ec.kuador.board.connections.domain.Connection;
import ec.kuador.board.connections.domain.McpClient.McpException;
import ec.kuador.board.connections.domain.McpClient.UnauthorizedException;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Un conector propio del tablero para un sistema que no publica servidor MCP (Outlook, Gmail): habla con su API en
 * nombre de la cuenta que inició sesión y ofrece herramientas como las de un servidor. Los de correo usan los mismos
 * nombres de herramienta, así un manual o un procedimiento sirve con cualquier proveedor.
 */
abstract class NativeConnector implements BuiltInConnector {

    static final JsonMapper JSON = JsonMapper.builder().build();
    /** Adjuntos que se traen por correo, y su tamaño máximo (los más grandes llegan sin contenido). */
    static final int MAX_FILES = 10;
    static final long MAX_FILE = 25L * 1024 * 1024;
    /** Texto de un correo que se guarda en la tarjeta. */
    static final int MAX_BODY_TEXT = 20_000;
    private static final int MAX_BODY = 30_000_000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String base;
    private final String system;

    NativeConnector(String base, String system) {
        this.base = base.replaceAll("/+$", "");
        this.system = system;
    }

    @Override
    public final boolean handles(String url) {
        return url != null && url.strip().replaceAll("/+$", "").equals(base);
    }


    final JsonNode get(String token, String path, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(java.net.URI.create(base + path)).GET();
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return exchange(token, b);
    }

    final JsonNode send(String token, String method, String path, Object body) {
        return exchange(token, HttpRequest.newBuilder(java.net.URI.create(base + path)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    private JsonNode exchange(String token, HttpRequest.Builder request) {
        if (token == null || token.isBlank()) throw new UnauthorizedException("Falta iniciar sesión en " + system + ".");
        HttpResponse<byte[]> res;
        try {
            res = http.send(request.timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + token).header("Accept", "application/json").build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new McpException("No se pudo hablar con " + system + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Se interrumpió la llamada a " + system + ".");
        }
        if (res.body().length > MAX_BODY) throw new McpException("La respuesta de " + system + " es demasiado grande.");
        String body = new String(res.body(), StandardCharsets.UTF_8);
        JsonNode json = null;
        try {
            if (!body.isBlank()) json = JSON.readTree(body);
        } catch (JacksonException e) {
            // Sin JSON: se informa solo el estado.
        }
        int status = res.statusCode();
        if (status == 401) throw new UnauthorizedException(system + " rechazó la sesión (401).");
        if (status / 100 != 2) {
            String why = json == null ? "" : json.path("error").path("message").asString("");
            if (status == 403) throw new McpException("La cuenta no dio permiso para eso en " + system + (why.isBlank() ? "." : ": " + clip(why)));
            if (status == 404) throw new McpException(system + " no encontró ese correo, adjunto o carpeta.");
            throw new McpException(system + " respondió " + status + (why.isBlank() ? "." : ": " + clip(why)));
        }
        return json == null ? JSON.createObjectNode() : json;
    }

    /** Las herramientas de correo, iguales en todos los proveedores. */
    static List<Connection.ToolDef> mailTools() {
        return List.of(
                tool("buscar_correos", "Lista correos de una carpeta, del más reciente al más antiguo. Devuelve id, remitente, asunto, fecha, si está leído, "
                        + "si trae adjuntos y un resumen. Usa el id con leer_correo.", true, props(
                                "carpeta", string("Carpeta: inbox (por defecto), sentitems, drafts, archive o junkemail."),
                                "texto", string("Palabras a buscar en asunto, remitente o cuerpo."),
                                "desde", string("Solo correos recibidos desde esta fecha (AAAA-MM-DD)."),
                                "solo_no_leidos", bool("Solo los que no se han leído."),
                                "con_adjuntos", bool("Solo los que traen adjuntos."),
                                "max", Map.of("type", "integer", "minimum", 1, "maximum", 25, "description", "Cuántos traer (10 por defecto).")), List.of()),
                tool("leer_correo", "El correo completo en texto: remitente, destinatarios, asunto, fecha, cuerpo y la lista de adjuntos (id, nombre, tipo, tamaño).",
                        true, props("id", string("Id del correo (de buscar_correos).")), List.of("id")),
                tool("leer_adjunto", "El contenido de un adjunto como texto (PDF, Word, XML, CSV). Una imagen o un Excel no se pueden leer desde aquí.", true,
                        props("correo", string("Id del correo."), "adjunto", string("Id del adjunto (de leer_correo).")), List.of("correo", "adjunto")),
                tool("marcar_leido", "Marca un correo como leído (o no leído), para no volver a procesarlo.", false,
                        props("id", string("Id del correo."), "leido", bool("true (por defecto) o false.")), List.of("id")),
                tool("responder_correo", "Responde al remitente de un correo, dentro de la misma conversación.", false,
                        props("id", string("Id del correo que se responde."), "cuerpo", string("Texto de la respuesta.")), List.of("id", "cuerpo")),
                tool("enviar_correo", "Envía un correo nuevo desde la cuenta conectada.", false,
                        props("para", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 1, "description", "Direcciones de los destinatarios."),
                                "asunto", string("Asunto."), "cuerpo", string("Texto del correo.")), List.of("para", "asunto", "cuerpo")));
    }

    /** El texto de un cuerpo HTML, sin etiquetas ni estilos. */
    static String plain(String html) {
        return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "").replaceAll("(?i)<br\\s*/?>|</p>|</div>|</tr>", "\n").replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replaceAll("\n{3,}", "\n\n");
    }

        /** Los destinatarios de «para», sea una lista o un texto separado por comas. */
    static List<String> recipients(Object para) {
        List<String> out = new java.util.ArrayList<>();
        for (Object address : para instanceof List<?> list ? list : List.of(String.valueOf(para == null ? "" : para).split("[,;\\s]+"))) {
            String mail = String.valueOf(address).strip();
            if (mail.isEmpty()) continue;
            if (!mail.matches("[^\\s<>@,;\"]+@[^\\s<>@,;\"]+")) throw new McpException("«" + clip(mail) + "» no es una dirección de correo.");
            out.add(mail);
        }
        if (out.isEmpty()) throw new McpException("Falta «para»: a quién se envía.");
        return out;
    }

    static int max(Map<String, Object> args) {
        try {
            return args.get("max") == null ? 10 : Math.max(1, Math.min(25, (int) Double.parseDouble(String.valueOf(args.get("max")))));
        } catch (NumberFormatException e) {
            throw new McpException("«max» debe ser un número.");
        }
    }

    static String since(Map<String, Object> args) {
        String since = text(args, "desde");
        if (since != null && !since.matches("\\d{4}-\\d{2}-\\d{2}")) throw new McpException("«desde» va como AAAA-MM-DD.");
        return since;
    }

    static String required(Map<String, Object> args, String name) {
        String v = text(args, name);
        if (v == null) throw new McpException("Falta «" + name + "».");
        return v;
    }

    static String text(Map<String, Object> args, String name) {
        Object v = args.get(name);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).strip();
    }

    static boolean truthy(Object v) {
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String clip(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    static Connection.ToolDef tool(String name, String description, boolean readOnly, Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return new Connection.ToolDef(name, description, schema, readOnly);
    }

    static Map<String, Object> props(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }
}
