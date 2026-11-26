package ec.kuador.board.docs.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadatos de un documento. El contenido vive en sus versiones. {@code issueKey} nulo = del
 * proyecto. {@code kind} es la clave del tipo en la metodología (SPEC, CONSTITUTION...). {@code subjectKey} es el proyecto en
 * que se trabaja: el de la tarjeta o, en un documento base, el suyo. {@code projectKey} es el tablero de la tarjeta; en un
 * documento base, el tablero desde donde se creó (es del proyecto y lo comparten todos sus tableros).
 */
public record Document(
        UUID id, String projectKey, String subjectKey, String issueKey, String kind, String title, String path, int currentVersion,
        Instant createdAt, Instant updatedAt, UUID updatedBy) {

    public Document withNewVersion(int version, UUID author, Instant now) {
        return new Document(id, projectKey, subjectKey, issueKey, kind, title, path, version, createdAt, now, author);
    }
}
