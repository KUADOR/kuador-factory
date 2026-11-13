package ec.kuador.board.attachment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** API pública del módulo (espacio de trabajo actual). */
public interface AttachmentApi {

    record AttachmentInfo(UUID id, String issueKey, String fileName, String contentType, long sizeBytes, UUID uploadedBy, Instant createdAt) {}

    List<AttachmentInfo> list(String issueKey);

    /** El issue de un adjunto (vacío si no existe). */
    Optional<String> issueOf(UUID attachmentId);

    /** Contenido de un adjunto de texto (hasta {@code maxBytes}); vacío si no es texto o es más grande. */
    Optional<String> readText(String issueKey, UUID attachmentId, int maxBytes);

    record FileContent(String fileName, String contentType, byte[] content) {}

    /** Contenido completo de un adjunto de la tarjeta (vacío si no existe o el almacenamiento no está configurado). */
    Optional<FileContent> content(String issueKey, UUID attachmentId);

    /** Adjunta un archivo a la tarjeta en nombre de {@code actorId} (p. ej. el informe que generó un agente). */
    AttachmentInfo attach(String issueKey, String fileName, String contentType, byte[] content, UUID actorId);

    /** Si hay almacenamiento configurado para adjuntos. */
    boolean available();
}
