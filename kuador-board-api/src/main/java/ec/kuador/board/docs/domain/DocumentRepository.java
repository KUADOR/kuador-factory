package ec.kuador.board.docs.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository {

    Optional<Document> findById(UUID id);

    /** Documentos de un proyecto en un tablero: los suyos y los de sus tarjetas. */
    List<Document> findByProject(String projectKey, String subjectKey);

    /** La tarjeta pasó a trabajarse en otro proyecto: sus documentos van con ella. */
    void moveIssue(String issueKey, String subjectKey);

    List<Document> findByIssue(String issueKey);

    boolean exists(String projectKey, String subjectKey, String issueKey, String kind);

    /** Inserta o actualiza los metadatos. Falla si otro guardado ganó la carrera (bloqueo optimista). */
    Document save(Document document);

    void saveVersion(DocumentVersion version);

    Optional<DocumentVersion> findVersion(UUID documentId, int version);

    /** Más reciente primero; sin contenido cargado en memoria más de lo necesario. */
    List<DocumentVersion> findVersions(UUID documentId);
}
