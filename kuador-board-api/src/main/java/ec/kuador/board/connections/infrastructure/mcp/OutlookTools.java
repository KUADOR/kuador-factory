package ec.kuador.board.connections.infrastructure.mcp;

import ec.kuador.board.connections.domain.Connection;
import ec.kuador.board.connections.domain.McpClient.McpException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Conector propio de Outlook (Microsoft 365 y cuentas personales): Microsoft no publica un servidor MCP para el correo,
 * así que el tablero habla con Microsoft Graph en nombre de la cuenta que inició sesión.
 */
@Component
class OutlookTools extends NativeConnector {

    private static final int MAX_MAIL_TEXT = 30_000;

    OutlookTools(@Value("${board.connectors.microsoft.graph-url:https://graph.microsoft.com/v1.0}") String graph) {
        super(graph, "Outlook");
    }

    @Override
    public boolean mailbox() {
        return true;
    }

    @Override
    public List<ec.kuador.board.connections.ConnectionsApi.IncomingMail> recent(String token, String carpeta, java.time.Instant since, int max) {
        String folder = carpeta == null || carpeta.isBlank() ? "inbox" : carpeta.strip();
        if (!folder.matches("[A-Za-z0-9_=\\-]{1,200}")) throw new McpException("Carpeta no válida: usa inbox, sentitems, drafts, archive o junkemail.");
        String filter = "receivedDateTime ge " + since.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        JsonNode list = get(token, "/me/mailFolders/" + folder + "/messages?$select=id,internetMessageId,subject,from,receivedDateTime,body,hasAttachments&$top="
                + Math.max(1, Math.min(max, 50)) + "&$orderby=" + enc("receivedDateTime asc") + "&$filter=" + enc(filter), "Prefer", "outlook.body-content-type=\"text\"");
        List<ec.kuador.board.connections.ConnectionsApi.IncomingMail> out = new ArrayList<>();
        for (JsonNode m : list.path("value")) {
            String id = m.path("id").asString("");
            List<ec.kuador.board.connections.ConnectionsApi.MailFile> files = new ArrayList<>();
            if (m.path("hasAttachments").asBoolean(false)) {
                for (JsonNode f : get(token, "/me/messages/" + enc(id) + "/attachments?$select=id,name,contentType,size,isInline").path("value")) {
                    if (f.path("isInline").asBoolean(false) || files.size() >= MAX_FILES) continue;
                    String name = f.path("name").asString("adjunto");
                    long size = f.path("size").asLong(0);
                    byte[] content = null;
                    if (size <= MAX_FILE) {
                        JsonNode full = get(token, "/me/messages/" + enc(id) + "/attachments/" + enc(f.path("id").asString("")));
                        if (full.path("contentBytes").isString()) {
                            try {
                                content = Base64.getDecoder().decode(full.path("contentBytes").asString());
                            } catch (IllegalArgumentException e) {
                                content = null;
                            }
                        }
                    }
                    files.add(new ec.kuador.board.connections.ConnectionsApi.MailFile(name, f.path("contentType").asString("application/octet-stream"), size, content));
                }
            }
            String body = m.path("body").path("content").asString("").strip();
            out.add(new ec.kuador.board.connections.ConnectionsApi.IncomingMail(id, blankToNull(m.path("internetMessageId").asString("")), address(m.path("from")),
                    m.path("subject").asString(""), java.time.Instant.parse(m.path("receivedDateTime").asString(since.toString())),
                    body.length() > MAX_BODY_TEXT ? body.substring(0, MAX_BODY_TEXT) + "\n… (correo recortado)" : body, files));
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @Override
    public List<Connection.ToolDef> tools(String token) {
        get(token, "/me?$select=id");
        return mailTools();
    }

    @Override
    public String call(String token, String tool, Map<String, Object> a) {
        Map<String, Object> args = a == null ? Map.of() : a;
        return switch (tool) {
            case "buscar_correos" -> search(token, args);
            case "leer_correo" -> read(token, required(args, "id"));
            case "leer_adjunto" -> attachment(token, required(args, "correo"), required(args, "adjunto"));
            case "marcar_leido" -> {
                boolean read = !"false".equalsIgnoreCase(String.valueOf(args.get("leido")));
                send(token, "PATCH", "/me/messages/" + enc(required(args, "id")), Map.of("isRead", read));
                yield read ? "Marcado como leído." : "Marcado como no leído.";
            }
            case "responder_correo" -> {
                send(token, "POST", "/me/messages/" + enc(required(args, "id")) + "/reply", Map.of("comment", required(args, "cuerpo")));
                yield "Respuesta enviada.";
            }
            case "enviar_correo" -> {
                List<Map<String, Object>> to = recipients(args.get("para")).stream().map(m -> Map.<String, Object>of("emailAddress", Map.of("address", m))).toList();
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
        StringBuilder q = new StringBuilder("/me/mailFolders/").append(folder).append("/messages?$select=id,subject,from,receivedDateTime,isRead,hasAttachments,bodyPreview&$top=")
                .append(max(args));
        String words = text(args, "texto");
        if (words != null) {
            // Graph no deja combinar la búsqueda de texto con filtros ni con orden.
            q.append("&$search=").append(enc("\"" + words.replace("\"", " ") + "\""));
        } else {
            String since = since(args);
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
        JsonNode m = get(token, "/me/messages/" + enc(id) + "?$select=subject,from,toRecipients,ccRecipients,receivedDateTime,body,hasAttachments,isRead",
                "Prefer", "outlook.body-content-type=\"text\"");
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
}
