package ec.kuador.board.attachment.domain;

import ec.kuador.board.shared.error.DomainException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Un archivo adjunto a una tarjeta. {@code storageKey}: dónde vive en el almacenamiento (nunca lleva el nombre del archivo). */
public record Attachment(UUID id, UUID workspaceId, String issueKey, String fileName, String contentType, long sizeBytes, String storageKey,
        UUID uploadedBy, Instant createdAt) {

    public static Attachment create(UUID workspaceId, String issueKey, String rawName, String rawType, long size, UUID uploadedBy, Instant now) {
        UUID id = UUID.randomUUID();
        return new Attachment(id, workspaceId, issueKey, cleanName(rawName), cleanType(rawType), size, workspaceId + "/" + issueKey + "/" + id, uploadedBy, now);
    }

    /** Nombre seguro para mostrar y descargar: sin rutas ni caracteres de control, hasta 255. */
    static String cleanName(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "archivo";
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    static String cleanType(String raw) {
        String type = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return type.matches("^[a-z0-9.+-]+/[a-z0-9.+-]+(;.*)?$") && type.length() <= 150 ? type.replaceFirst(";.*$", "") : "application/octet-stream";
    }

    /** Texto que un agente puede leer. */
    public boolean textual() {
        return contentType.startsWith("text/") || contentType.equals("application/json") || contentType.equals("application/xml")
                || contentType.equals("application/yaml") || fileName.matches("(?i).+\\.(md|txt|csv|json|ya?ml|xml)$");
    }

    public static void checkSize(long size, long max) {
        if (size <= 0) throw new DomainException("El archivo está vacío.");
        if (size > max) throw new DomainException("El archivo pesa más de " + (max / (1024 * 1024)) + " MB, el máximo por adjunto.");
    }
}
