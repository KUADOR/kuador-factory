package ec.kuador.board.connections.infrastructure.mcp;

import ec.kuador.board.connections.domain.Connection;
import ec.kuador.board.connections.domain.McpClient.McpException;
import ec.kuador.board.connections.domain.McpClient.UnauthorizedException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conector propio de Outlook (Microsoft 365): Microsoft no publica un servidor MCP para el correo, así que el tablero
 * habla directo con Microsoft Graph en nombre de la cuenta que inició sesión y ofrece las mismas herramientas que
 * ofrecería un servidor: buscar y leer correos, leer adjuntos, y (si se habilitan) enviar, responder y marcar leído.
 */
@Component
class OutlookTools {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_BODY = 30_000_000;
    private static final int MAX_MAIL_TEXT = 30_000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String graph;

    OutlookTools(@Value("${board.connectors.microsoft.graph-url:https://graph.microsoft.com/v1.0}") String graph) {
        this.graph = graph.replaceAll("/+$", "");
    }

    /** Si la dirección de la conexión es la de este conector (y no la de un servidor MCP). */
    boolean handles(String url) {
        return url != null && url.strip().replaceAll("/+$", "").equals(graph);
    }

    List<Connection.ToolDef> tools(String token) {
        // Comprueba la sesión antes de ofrecer nada: si no sirve, que se sepa al conectar y no en la primera tarjeta.
        get(token, "/me?$select=id");
        return List.of(
                tool("buscar_correos", "Lista correos de una carpeta, del más reciente al más antiguo. Devuelve id, remitente, asunto, fecha, si está leído, "
                        + "si trae adjuntos y un resumen. Usa el id con leer_correo.", true, props(
                                "carpeta", string("Carpeta: inbox (por defecto), sentitems, drafts, archive o junkemail."),
                                "texto", string("Palabras a buscar en asunto, remitente o cuerpo. Con texto no se aplican los demás filtros."),
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

    String call(String token, String tool, Map<String, Object> a) {
        Map<String, Object> args = a == null ? Map.of() : a;
        return switch (tool) {
            case "buscar_correos" -> search(token, args);
            case "leer_correo" -> read(token, required(args, "id"));
            case "leer_adjunto" -> attachment(token, required(args, "correo"), required(args, "adjunto"));
            case "marcar_leido" -> {
                boolean read = !Boolean.FALSE.equals(args.get("leido")) && !"false".equals(String.valueOf(args.get("leido")));
                send(token, "PATCH", "/me/messages/" + enc(required(args, "id")), Map.of("isRead", read));
                yield read ? "Marcado como leído." : "Marcado como no leído.";
            }
            case "responder_correo" -> {
                send(token, "POST", "/me/messages/" + enc(required(args, "id")) + "/reply", Map.of("comment", required(args, "cuerpo")));
                yield "Respuesta enviada.";
            }
            case "enviar_correo" -> {
                List<Map<String, Object>> to = new ArrayList<>();
                Object para = args.get("para");
                for (Object address : para instanceof List<?> list ? list : List.of(String.valueOf(para == null ? "" : para).split("[,;\\s]+"))) {
                    String mail = String.valueOf(address).strip();
                    if (!mail.isEmpty()) to.add(Map.of("emailAddress", Map.of("address", mail)));
                }
                if (to.isEmpty()) throw new McpException("Falta «para»: a quién se envía.");
                send(token, "POST", "/me/sendMail", Map.of("message", Map.of("subject", required(args, "asunto"),
                        "body", Map.of("contentType", "Text", "content", required(args, "cuerpo")), "toRecipients", to), "saveToSentItems", true));
                yield "Correo enviado a " + to.size() + (to.size() == 1 ? " destinatario." : " destinatarios.");
            }
            default -> throw new McpException("Outlook no tiene la herramienta «" + tool + "».");
        };
    }

    private String search(String token, Map<String, Object> args) {
        String folder = text(args, "carpeta");
        if (folder == null) folder = "inbox";
        if (!folder.matches("[A-Za-z0-9_=\\-]{1,200}")) throw new McpException("Carpeta no válida: usa inbox, sentitems, drafts, archive o junkemail.");
        int top = 10;
        try {
            if (args.get("max") != null) top = Math.max(1, Math.min(25, (int) Double.parseDouble(String.valueOf(args.get("max")))));
        } catch (NumberFormatException e) {
            throw new McpException("«max» debe ser un número.");
        }
        StringBuilder q = new StringBuilder("/me/mailFolders/").append(folder).append("/messages?$select=id,subject,from,receivedDateTime,isRead,hasAttachments,bodyPreview&$top=")
                .append(top);
        String words = text(args, "texto");
        if (words != null) {
            // Graph no deja combinar la búsqueda de texto con filtros ni con orden.
            q.append("&$search=").append(enc("\"" + words.replace("\"", " ") + "\""));
        } else {
            String since = text(args, "desde");
            if (since != null && !since.matches("\\d{4}-\\d{2}-\\d{2}")) throw new McpException("«desde» va como AAAA-MM-DD.");
            // El campo por el que se ordena tiene que ir primero en el filtro.
            StringBuilder filter = new StringBuilder("receivedDateTime ge ").append(since == null ? "1900-01-01" : since).append("T00:00:00Z");
            if (truthy(args.get("solo_no_leidos"))) filter.append(" and isRead eq false");
            if (truthy(args.get("con_adjuntos"))) filter.append(" and hasAttachments eq true");
            q.append("&$orderby=").append(enc("receivedDateTime desc")).append("&$filter=").append(enc(filter.toString()));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode m : get(token, q.toString()).path("value")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.path("id").asString(""));
            row.put("de", address(m.path("from")));
            row.put("asunto", m.path("subject").asString(""));
            row.put("fecha", m.path("receivedDateTime").asString(""));
            row.put("leido", m.path("isRead").asBoolean(false));
            row.put("adjuntos", m.path("hasAttachments").asBoolean(false));
            row.put("resumen", m.path("bodyPreview").asString(""));
            out.add(row);
        }
        return out.isEmpty() ? "No hay correos que cumplan eso." : JSON.writeValueAsString(out);
    }

    private String read(String token, String id) {
        JsonNode m = get(token, "/me/messages/" + enc(id) + "?$select=subject,from,toRecipients,ccRecipients,receivedDateTime,body,hasAttachments,isRead");
        StringBuilder out = new StringBuilder();
        out.append("De: ").append(address(m.path("from"))).append('\n');
        out.append("Para: ").append(addresses(m.path("toRecipients"))).append('\n');
        if (!m.path("ccRecipients").isEmpty()) out.append("Copia: ").append(addresses(m.path("ccRecipients"))).append('\n');
        out.append("Fecha: ").append(m.path("receivedDateTime").asString("")).append('\n');
        out.append("Asunto: ").append(m.path("subject").asString("")).append("\n\n");
        String body = m.path("body").path("content").asString("").strip();
        out.append(body.length() > MAX_MAIL_TEXT ? body.substring(0, MAX_MAIL_TEXT) + "\n… (correo recortado)" : body);
        if (m.path("hasAttachments").asBoolean(false)) {
            out.append("\n\nAdjuntos (léelos con leer_adjunto):\n");
            for (JsonNode f : get(token, "/me/messages/" + enc(id) + "/attachments?$select=id,name,contentType,size,isInline").path("value")) {
                if (f.path("isInline").asBoolean(false)) continue;
                out.append("- ").append(f.path("name").asString("sin nombre")).append(" (").append(f.path("contentType").asString("?")).append(", ")
                        .append(Math.max(1, f.path("size").asLong(0) / 1024)).append(" KB) id: ").append(f.path("id").asString("")).append('\n');
            }
        }
        return out.toString().strip();
    }

    private String attachment(String token, String mail, String id) {
        JsonNode f = get(token, "/me/messages/" + enc(mail) + "/attachments/" + enc(id));
        String name = f.path("name").asString("adjunto");
        if (!f.path("contentBytes").isString()) {
            return "[Archivo «" + name + "»: no es un archivo (es otro correo o un enlace adjunto) y no se puede leer desde aquí.]";
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(f.path("contentBytes").asString());
        } catch (IllegalArgumentException e) {
            throw new McpException("El adjunto llegó dañado.");
        }
        return ToolFiles.read(name, f.path("contentType").asString(""), bytes);
    }

    private JsonNode get(String token, String path) {
        return exchange(token, HttpRequest.newBuilder(URI.create(graph + path)).header("Prefer", "outlook.body-content-type=\"text\"").GET());
    }

    private void send(String token, String method, String path, Map<String, Object> body) {
        exchange(token, HttpRequest.newBuilder(URI.create(graph + path)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    private JsonNode exchange(String token, HttpRequest.Builder request) {
        if (token == null || token.isBlank()) throw new UnauthorizedException("Falta iniciar sesión en Outlook.");
        HttpResponse<byte[]> res;
        try {
            res = http.send(request.timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + token).header("Accept", "application/json").build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new McpException("No se pudo hablar con Outlook: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Se interrumpió la llamada a Outlook.");
        }
        if (res.body().length > MAX_BODY) throw new McpException("La respuesta de Outlook es demasiado grande.");
        String body = new String(res.body(), StandardCharsets.UTF_8);
        JsonNode json = null;
        try {
            if (!body.isBlank()) json = JSON.readTree(body);
        } catch (JacksonException e) {
            // Sin JSON: se informa solo el estado.
        }
        int status = res.statusCode();
        if (status == 401) throw new UnauthorizedException("Outlook rechazó la sesión (401).");
        if (status / 100 != 2) {
            String why = json == null ? "" : json.path("error").path("message").asString("");
            if (status == 403) throw new McpException("La cuenta no dio permiso para eso en Outlook" + (why.isBlank() ? "." : ": " + clip(why)));
            if (status == 404) throw new McpException("Outlook no encontró ese correo, adjunto o carpeta.");
            throw new McpException("Outlook respondió " + status + (why.isBlank() ? "." : ": " + clip(why)));
        }
        return json == null ? JSON.createObjectNode() : json;
    }

    private static String address(JsonNode who) {
        JsonNode e = who.path("emailAddress");
        String name = e.path("name").asString("");
        String mail = e.path("address").asString("");
        return name.isBlank() || name.equals(mail) ? mail : name + " <" + mail + ">";
    }

    private static String addresses(JsonNode list) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : list) out.add(address(r));
        return String.join(", ", out);
    }

    private static String required(Map<String, Object> args, String name) {
        String v = text(args, name);
        if (v == null) throw new McpException("Falta «" + name + "».");
        return v;
    }

    private static String text(Map<String, Object> args, String name) {
        Object v = args.get(name);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).strip();
    }

    private static boolean truthy(Object v) {
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String clip(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static Connection.ToolDef tool(String name, String description, boolean readOnly, Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return new Connection.ToolDef(name, description, schema, readOnly);
    }

    private static Map<String, Object> props(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }
}
