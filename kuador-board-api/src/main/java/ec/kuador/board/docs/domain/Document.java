package ec.kuador.board.docs.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadatos de un documento. El contenido vive en sus versiones. {@code issueKey} nulo = del
 * proyecto. {@code kind} es la clave del tipo en la metodología (SPEC, CONSTITUTION...).
 */
public record Document(
        UUID id, String projectKey, String issueKey, String kind, String title, String path, int currentVersion,
        Instant createdAt, Instant updatedAt, UUID updatedBy) {

    public Document withNewVersion(int version, UUID author, Instant now) {
        return new Document(id, projectKey, issueKey, kind, title, path, version, createdAt, now, author);
    }
}
