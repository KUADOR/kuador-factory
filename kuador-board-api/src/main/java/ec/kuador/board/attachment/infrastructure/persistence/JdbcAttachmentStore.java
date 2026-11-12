package ec.kuador.board.attachment.infrastructure.persistence;

import ec.kuador.board.attachment.domain.Attachment;
import ec.kuador.board.attachment.domain.AttachmentStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL directo; cada consulta filtra por el espacio que recibe. */
@Repository
class JdbcAttachmentStore implements AttachmentStore {

    private static final String COLUMNS = "id, workspace_id, issue_key, file_name, content_type, size_bytes, storage_key, uploaded_by, created_at";

    private final JdbcClient jdbc;

    JdbcAttachmentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Attachment a) {
        jdbc.sql("insert into attachment (" + COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(a.id(), a.workspaceId(), a.issueKey(), a.fileName(), a.contentType(), a.sizeBytes(), a.storageKey(), a.uploadedBy(),
                        Timestamp.from(a.createdAt()))
                .update();
    }

    @Override
    public List<Attachment> byIssue(UUID workspaceId, String issueKey) {
        return jdbc.sql("select " + COLUMNS + " from attachment where workspace_id = ? and issue_key = ? order by created_at")
                .params(workspaceId, issueKey).query((rs, n) -> map(rs)).list();
    }

    @Override
    public List<Attachment> byProject(UUID workspaceId, String projectKey) {
        return jdbc.sql("select " + COLUMNS + " from attachment where workspace_id = ? and issue_key like ? order by created_at")
                .params(workspaceId, projectKey.replace("\\", "").replace("%", "").replace("_", "\\_") + "-%").query((rs, n) -> map(rs)).list();
    }

    @Override
    public Optional<Attachment> find(UUID workspaceId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from attachment where workspace_id = ? and id = ?").params(workspaceId, id)
                .query((rs, n) -> map(rs)).optional();
    }

    @Override
    public void delete(UUID workspaceId, UUID id) {
        jdbc.sql("delete from attachment where workspace_id = ? and id = ?").params(workspaceId, id).update();
    }

    private static Attachment map(ResultSet rs) throws SQLException {
        return new Attachment(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class), rs.getString("issue_key"),
                rs.getString("file_name"), rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("storage_key"),
                rs.getObject("uploaded_by", UUID.class), rs.getTimestamp("created_at").toInstant());
    }
}
