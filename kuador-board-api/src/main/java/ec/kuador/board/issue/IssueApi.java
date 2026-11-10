package ec.kuador.board.issue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** API pública del módulo para el resto de la aplicación. */
public interface IssueApi {

    /** Datos mínimos de un issue: clave, proyecto, número y título. */
    record IssueRef(String key, String projectKey, int number, String title) {}

    Optional<IssueRef> find(String issueKey);

    /** Crea un issue en la primera columna del proyecto (p. ej. al importar una spec del repo). */
    IssueRef createForImport(String projectKey, String title, UUID actorId);

    record CommentView(UUID authorId, String body, Instant createdAt) {}

    /** Todo lo que un agente necesita saber de un issue. Enums como texto. */
    record IssueContext(
            String key, String projectKey, String title, String description, String type, String status, String priority,
            Integer storyPoints, List<String> labels, String parentKey, UUID assigneeId, UUID reporterId, List<CommentView> comments) {}

    Optional<IssueContext> context(String issueKey);

    /** Comenta como {@code authorId} (persona o agente); queda en el historial. */
    void addComment(String issueKey, UUID authorId, String body);

    void reassign(String issueKey, UUID memberId, UUID actorId);

    /** Pone los puntos solo si el issue no tenía: nunca pisa una estimación humana. */
    boolean setStoryPointsIfEmpty(String issueKey, int points, UUID actorId);
}
