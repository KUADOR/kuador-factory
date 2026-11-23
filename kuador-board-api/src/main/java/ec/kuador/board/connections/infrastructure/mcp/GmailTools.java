package ec.kuador.board.connections.infrastructure.mcp;

import ec.kuador.board.connections.domain.Connection;
import ec.kuador.board.connections.domain.McpClient.McpException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Conector propio de Gmail sobre la API de Gmail: mismas herramientas que Outlook. No usa el servidor MCP de Google
 * (en vista previa, sin envío ni lectura de adjuntos garantizada). El id de un adjunto es el de su parte del correo,
 * porque el de Gmail cambia en cada lectura.
 */
@Component
class GmailTools extends NativeConnector {

    private static final int MAX_MAIL_TEXT = 30_000;
    private static final Pattern ANGLE = Pattern.compile("<([^<>\\s]+@[^<>\\s]+)>");
    private static final Map<String, String> FOLDERS = Map.of("inbox", "in:inbox", "sentitems", "in:sent", "drafts", "in:drafts", "junkemail", "in:spam",
            "archive", "-in:inbox -in:sent -in:drafts -in:spam -in:trash");

    GmailTools(@Value("${board.connectors.google.gmail-url:https://gmail.googleapis.com/gmail/v1}") String gmail) {
        super(gmail, "Gmail");
    }

    @Override
    List<Connection.ToolDef> tools(String token) {
        get(token, "/users/me/profile");
        return mailTools();
    }

    @Override
    String call(String token, String tool, Map<String, Object> a) {
        Map<String, Object> args = a == null ? Map.of() : a;
        return switch (tool) {
            case "buscar_correos" -> search(token, args);
            case "leer_correo" -> read(token, required(args, "id"));
            case "leer_adjunto" -> attachment(token, required(args, "correo"), required(args, "adjunto"));
            case "marcar_leido" -> {
                boolean read = !"false".equalsIgnoreCase(String.valueOf(args.get("leido")));
                send(token, "POST", "/users/me/messages/" + enc(required(args, "id")) + "/modify",
                        Map.of(read ? "removeLabelIds" : "addLabelIds", List.of("UNREAD")));
                yield read ? "Marcado como leído." : "Marcado como no leído.";
            }
            case "responder_correo" -> reply(token, required(args, "id"), required(args, "cuerpo"));
            case "enviar_correo" -> {
                List<String> to = recipients(args.get("para"));
                send(token, "POST", "/users/me/messages/send", Map.of("raw", mime(String.join(", ", to), required(args, "asunto"), required(args, "cuerpo"), null, null)));
                yield "Correo enviado a " + to.size() + (to.size() == 1 ? " destinatario." : " destinatarios.");
            }
            default -> throw new McpException("Gmail no tiene la herramienta «" + tool + "».");
        };
    }

    private String search(String token, Map<String, Object> args) {
        String folder = text(args, "carpeta");
        List<String> q = new ArrayList<>();
        if (folder == null) {
            q.add("in:inbox");
        } else if (FOLDERS.containsKey(folder.toLowerCase(Locale.ROOT))) {
            q.add(FOLDERS.get(folder.toLowerCase(Locale.ROOT)));
        } else if (folder.matches("[\\p{L}\\p{N} _/\\-]{1,100}")) {
            q.add("label:\"" + folder + "\"");
        } else {
            throw new McpException("Carpeta no válida: usa inbox, sentitems, drafts, archive, junkemail o el nombre de una etiqueta.");
        }
        String words = text(args, "texto");
        if (words != null) q.add(words.replace("\n", " "));
        String since = since(args);
        if (since != null) q.add("after:" + since.replace('-', '/'));
        if (truthy(args.get("solo_no_leidos"))) q.add("is:unread");
        if (truthy(args.get("con_adjuntos"))) q.add("has:attachment");
        JsonNode found = get(token, "/users/me/messages?maxResults=" + max(args) + "&q=" + enc(String.join(" ", q)));
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode ref : found.path("messages")) {
            JsonNode m = get(token, "/users/me/messages/" + enc(ref.path("id").asString("")) + "?format=full");
            Map<String, String> h = headers(m.path("payload"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.path("id").asString(""));
            row.put("de", h.getOrDefault("from", ""));
            row.put("asunto", h.getOrDefault("subject", ""));
            row.put("fecha", h.getOrDefault("date", ""));
            row.put("leido", !labels(m).contains("UNREAD"));
            row.put("adjuntos", !files(m.path("payload"), new ArrayList<>()).isEmpty());
            row.put("resumen", m.path("snippet").asString(""));
            out.add(row);
        }
        return out.isEmpty() ? "No hay correos que cumplan eso." : JSON.writeValueAsString(out);
    }

    private String read(String token, String id) {
        JsonNode m = get(token, "/users/me/messages/" + enc(id) + "?format=full");
        Map<String, String> h = headers(m.path("payload"));
        StringBuilder out = new StringBuilder();
        out.append("De: ").append(h.getOrDefault("from", "")).append('\n');
        out.append("Para: ").append(h.getOrDefault("to", "")).append('\n');
        if (h.containsKey("cc")) out.append("Copia: ").append(h.get("cc")).append('\n');
        out.append("Fecha: ").append(h.getOrDefault("date", "")).append('\n');
        out.append("Asunto: ").append(h.getOrDefault("subject", "")).append("\n\n");
        String body = body(m.path("payload"), "text/plain");
        if (body == null) {
            String html = body(m.path("payload"), "text/html");
            body = html == null ? m.path("snippet").asString("") : html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "").replaceAll("(?i)<br\\s*/?>|</p>|</div>|</tr>", "\n")
                    .replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replaceAll("\n{3,}", "\n\n");
        }
        body = body.strip();
        out.append(body.length() > MAX_MAIL_TEXT ? body.substring(0, MAX_MAIL_TEXT) + "\n… (correo recortado)" : body);
        List<JsonNode> files = files(m.path("payload"), new ArrayList<>());
        if (!files.isEmpty()) {
            out.append("\n\nAdjuntos (léelos con leer_adjunto):\n");
            for (JsonNode f : files) {
                out.append("- ").append(f.path("filename").asString("sin nombre")).append(" (").append(f.path("mimeType").asString("?")).append(", ")
                        .append(Math.max(1, f.path("body").path("size").asLong(0) / 1024)).append(" KB) id: ").append(f.path("partId").asString("")).append('\n');
            }
        }
        return out.toString().strip();
    }

    private String attachment(String token, String mail, String id) {
        JsonNode m = get(token, "/users/me/messages/" + enc(mail) + "?format=full");
        JsonNode part = files(m.path("payload"), new ArrayList<>()).stream()
                .filter(f -> id.equals(f.path("partId").asString("")) || id.equals(f.path("body").path("attachmentId").asString(""))).findFirst()
                .orElseThrow(() -> new McpException("Ese correo no tiene el adjunto " + clip(id) + "; tómalo de leer_correo."));
        String data = part.path("body").path("data").asString("");
        if (data.isBlank()) {
            String attachmentId = part.path("body").path("attachmentId").asString("");
            data = get(token, "/users/me/messages/" + enc(mail) + "/attachments/" + enc(attachmentId)).path("data").asString("");
        }
        return ToolFiles.read(part.path("filename").asString("adjunto"), part.path("mimeType").asString(""), decode(data));
    }

    private String reply(String token, String id, String body) {
        JsonNode m = get(token, "/users/me/messages/" + enc(id) + "?format=metadata&metadataHeaders=From&metadataHeaders=Reply-To&metadataHeaders=Subject"
                + "&metadataHeaders=Message-ID&metadataHeaders=References");
        Map<String, String> h = headers(m.path("payload"));
        String to = address(h.containsKey("reply-to") ? h.get("reply-to") : h.getOrDefault("from", ""));
        if (to == null) throw new McpException("No se sabe a quién responder: el correo no tiene remitente.");
        String subject = h.getOrDefault("subject", "");
        if (!subject.regionMatches(true, 0, "Re:", 0, 3)) subject = "Re: " + subject;
        String messageId = h.get("message-id");
        String references = messageId == null ? null : (h.containsKey("references") ? h.get("references") + " " : "") + messageId;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("raw", mime(to, subject, body, messageId, references));
        if (m.path("threadId").isString()) request.put("threadId", m.path("threadId").asString());
        send(token, "POST", "/users/me/messages/send", request);
        return "Respuesta enviada.";
    }

    /** Un correo de texto en el formato que pide Gmail (RFC 2822 en base64 para direcciones web). */
    static String mime(String to, String subject, String body, String inReplyTo, String references) {
        StringBuilder m = new StringBuilder();
        m.append("To: ").append(header(to)).append("\r\n");
        m.append("Subject: =?UTF-8?B?").append(Base64.getEncoder().encodeToString(header(subject).getBytes(StandardCharsets.UTF_8))).append("?=\r\n");
        if (inReplyTo != null) m.append("In-Reply-To: ").append(header(inReplyTo)).append("\r\n");
        if (references != null) m.append("References: ").append(header(references)).append("\r\n");
        m.append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=\"UTF-8\"\r\nContent-Transfer-Encoding: base64\r\n\r\n");
        m.append(Base64.getMimeEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(m.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Un valor de cabecera sin saltos de línea: así nadie agrega destinatarios ni cabeceras desde un asunto. */
    private static String header(String value) {
        return value.replaceAll("[\\r\\n]+", " ").strip();
    }

    /** La dirección de un «Nombre <correo>» (o el texto si ya es una dirección). */
    private static String address(String value) {
        Matcher m = ANGLE.matcher(value);
        if (m.find()) return m.group(1);
        String v = value.strip();
        return v.matches("[^\\s<>@,;\"]+@[^\\s<>@,;\"]+") ? v : null;
    }

    private static Map<String, String> headers(JsonNode payload) {
        Map<String, String> h = new LinkedHashMap<>();
        for (JsonNode x : payload.path("headers")) h.putIfAbsent(x.path("name").asString("").toLowerCase(Locale.ROOT), x.path("value").asString(""));
        return h;
    }

    private static List<String> labels(JsonNode m) {
        List<String> out = new ArrayList<>();
        for (JsonNode l : m.path("labelIds")) out.add(l.asString(""));
        return out;
    }

    /** Las partes que son archivos adjuntos (con nombre), sin las imágenes metidas en el cuerpo (firmas, logos). */
    private static List<JsonNode> files(JsonNode part, List<JsonNode> out) {
        if (!part.path("filename").asString("").isBlank()) {
            Map<String, String> h = headers(part);
            boolean inline = h.getOrDefault("content-disposition", "").toLowerCase(Locale.ROOT).startsWith("inline") && h.containsKey("content-id");
            if (!inline) out.add(part);
        }
        for (JsonNode p : part.path("parts")) files(p, out);
        return out;
    }

    /** El primer cuerpo de ese tipo que no sea un adjunto. */
    private static String body(JsonNode part, String type) {
        if (type.equalsIgnoreCase(part.path("mimeType").asString("")) && part.path("filename").asString("").isBlank() && part.path("body").path("data").isString()) {
            return new String(decode(part.path("body").path("data").asString()), StandardCharsets.UTF_8);
        }
        for (JsonNode p : part.path("parts")) {
            String found = body(p, type);
            if (found != null) return found;
        }
        return null;
    }

    private static byte[] decode(String data) {
        try {
            return Base64.getUrlDecoder().decode(data.replaceAll("\\s", "").replace('+', '-').replace('/', '_'));
        } catch (IllegalArgumentException e) {
            throw new McpException("El contenido llegó dañado.");
        }
    }
}
