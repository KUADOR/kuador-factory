package ec.kuador.board.docs.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadatos de un documento. El contenido vive en sus versiones. {@code issueKey} nulo = del
 * proyecto. {@code kind} es la clave del tipo en la metodología (SPEC, CONSTITUTION...). {@code projectKey} es el tablero y
 * {@code subjectKey} el proyecto en que se trabaja: el de la tarjeta o, en un documento base, el suyo.
 */
public record Document(
        UUID id, String projectKey, String subjectKey, String issueKey, String kind, String title, String path, int currentVersion,
        Instant createdAt, Instant updatedAt, UUID updatedBy) {

    public Document withNewVersion(int version, UUID author, Instant now) {
        return new Document(id, projectKey, subjectKey, issueKey, kind, title, path, version, createdAt, now, author);
    }
}
