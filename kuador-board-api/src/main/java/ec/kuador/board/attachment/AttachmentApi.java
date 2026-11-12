package ec.kuador.board.attachment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** API pública del módulo (espacio de trabajo actual). */
public interface AttachmentApi {

    record AttachmentInfo(UUID id, String issueKey, String fileName, String contentType, long sizeBytes, UUID uploadedBy, Instant createdAt) {}

    List<AttachmentInfo> list(String issueKey);

    /** Contenido de un adjunto de texto (hasta {@code maxBytes}); vacío si no es texto o es más grande. */
    Optional<String> readText(String issueKey, UUID attachmentId, int maxBytes);
}
