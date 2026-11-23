package ec.kuador.board.connections.infrastructure.mcp;

import ec.kuador.board.shared.files.DocumentText;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Archivos que devuelve una herramienta (el adjunto de un correo, una factura, un reporte) convertidos al texto que un
 * agente puede leer. No depende de quién sea el proveedor: reconoce el archivo por su contenido, venga como recurso de
 * MCP o como base64 dentro de un JSON (así los entregan Gmail, Microsoft Graph y la mayoría de servidores de correo).
 */
final class ToolFiles {

    /** Texto que se entrega por archivo: una factura cabe de sobra; un libro, no. */
    static final int MAX_TEXT = 60_000;
    private static final int MIN_BASE64 = 200;
    private static final Pattern BASE64 = Pattern.compile("^[A-Za-z0-9+/_\\-\\s]+={0,2}\\s*$");
    private static final List<String> NAME_FIELDS = List.of("filename", "fileName", "file_name", "name", "title");
    private static final List<String> TYPE_FIELDS = List.of("mimeType", "mime_type", "contentType", "content_type", "mediaType", "type");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ToolFiles() {}

    /** El archivo como texto para el agente, con su nombre; si no se puede leer, dice por qué. */
    static String read(String name, String contentType, byte[] content) {
        String label = name == null || name.isBlank() ? "sin nombre" : name.strip();
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).replaceAll(";.*$", "").strip();
        String kind = sniff(content);
        if (kind.equals("image") || type.startsWith("image/")) {
            return "[Archivo «" + label + "»: es una imagen (" + content.length / 1024 + " KB) y desde aquí no se puede ver su contenido. "
                    + "Si es un documento escaneado, pide a una persona que lo revise.]";
        }
        if (type.startsWith("audio/") || type.startsWith("video/")) return "[Archivo «" + label + "»: audio o video, no se puede leer desde aquí.]";
        String fileName = label;
        if (kind.equals("pdf") && !label.toLowerCase(Locale.ROOT).endsWith(".pdf")) fileName = label + ".pdf";
        DocumentText.Extracted found;
        if (kind.equals("text") && !type.equals("application/pdf")) {
            // Texto de cualquier tipo (XML de una factura electrónica, CSV, JSON), aunque el servidor lo anuncie como binario.
            found = new DocumentText.Extracted("texto", new String(content, StandardCharsets.UTF_8), null);
        } else if (kind.equals("zip") && (type.contains("spreadsheet") || fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx"))) {
            found = new DocumentText.Extracted("Excel", null, "Es una planilla de Excel: desde aquí no se lee; pide los datos como CSV o PDF.");
        } else {
            if (kind.equals("zip") && type.contains("wordprocessing") && !fileName.toLowerCase(Locale.ROOT).endsWith(".docx")) fileName = label + ".docx";
            found = DocumentText.extract(fileName, type.isEmpty() ? null : type, content);
        }
        if (found.text() == null || found.text().isBlank()) return "[Archivo «" + label + "»: " + (found.note() == null ? "no tiene texto." : found.note()) + "]";
        String text = found.text().strip();
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT) + "\n… (archivo recortado)";
        return "[Archivo «" + label + "» (" + found.kind() + ")" + (found.note() == null ? "" : " · " + found.note()) + "]\n" + text;
    }

    /**
     * Si el texto es un JSON que trae archivos en base64 (un adjunto con su nombre, el cuerpo de un correo), los cambia
     * por su contenido legible. Lo demás vuelve tal cual.
     */
    static String unwrap(String text) {
        String t = text.strip();
        if (t.length() < MIN_BASE64 || !(t.startsWith("{") || t.startsWith("["))) return text;
        JsonNode root;
        try {
            root = JSON.readTree(t);
        } catch (JacksonException e) {
            return text;
        }
        return replace(root) ? JSON.writeValueAsString(root) : text;
    }

    private static boolean replace(JsonNode node) {
        boolean changed = false;
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : List.copyOf(object.properties())) {
                JsonNode value = field.getValue();
                if (value.isString()) {
                    String readable = decode(value.asString(), sibling(object, NAME_FIELDS), sibling(object, TYPE_FIELDS));
                    if (readable != null) {
                        object.put(field.getKey(), readable);
                        changed = true;
                    }
                } else if (value.isContainer()) {
                    changed |= replace(value);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) changed |= replace(item);
        }
        return changed;
    }

    /** El contenido legible de un texto en base64, o nulo si no es un archivo reconocible (se deja como vino). */
    private static String decode(String value, String name, String type) {
        if (value.length() < MIN_BASE64 || !BASE64.matcher(value).matches()) return null;
        byte[] bytes;
        try {
            String compact = value.replaceAll("\\s", "");
            bytes = compact.contains("-") || compact.contains("_") ? Base64.getUrlDecoder().decode(compact) : Base64.getDecoder().decode(compact);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String kind = sniff(bytes);
        if (kind.equals("unknown") && name == null) return null;
        // Texto suelto sin nombre: el cuerpo de un correo. Se entrega sin el marco de archivo.
        if (kind.equals("text") && name == null) {
            String body = new String(bytes, StandardCharsets.UTF_8);
            return body.length() > MAX_TEXT ? body.substring(0, MAX_TEXT) + "\n… (recortado)" : body;
        }
        return read(name, type, bytes);
    }

    private static String sibling(ObjectNode object, List<String> names) {
        for (String n : names) {
            JsonNode v = object.get(n);
            if (v != null && v.isString() && !v.asString().isBlank() && v.asString().length() <= 300) return v.asString();
        }
        return null;
    }

    /** Qué es por sus primeros bytes: pdf, zip (Word, Excel), image, text o unknown. */
    static String sniff(byte[] b) {
        if (b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "pdf";
        if (b.length >= 4 && b[0] == 'P' && b[1] == 'K' && (b[2] == 3 || b[2] == 5)) return "zip";
        if (b.length >= 4 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image";
        if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) return "image";
        if (b.length >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image";
        if (b.length == 0) return "unknown";
        for (int i = 0; i < Math.min(b.length, 8192); i++) {
            int c = b[i] & 0xff;
            if (c < 0x09 || (c > 0x0d && c < 0x20)) return "unknown";
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b));
            return "text";
        } catch (CharacterCodingException e) {
            return "unknown";
        }
    }
}
