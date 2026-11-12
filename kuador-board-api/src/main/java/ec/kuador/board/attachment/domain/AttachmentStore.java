package ec.kuador.board.attachment.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttachmentStore {

    void save(Attachment attachment);

    List<Attachment> byIssue(UUID workspaceId, String issueKey);

    /** Los de todas las tarjetas de un tablero (sus claves empiezan con «CLAVE-»). */
    List<Attachment> byProject(UUID workspaceId, String projectKey);

    Optional<Attachment> find(UUID workspaceId, UUID id);

    void delete(UUID workspaceId, UUID id);
}
