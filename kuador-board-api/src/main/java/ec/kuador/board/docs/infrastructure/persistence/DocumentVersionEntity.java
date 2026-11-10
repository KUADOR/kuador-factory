package ec.kuador.board.docs.infrastructure.persistence;

import ec.kuador.board.docs.domain.DocumentVersion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "document_version")
@IdClass(DocumentVersionEntity.Key.class)
class DocumentVersionEntity {

    record Key(UUID documentId, int version) implements Serializable {
        Key() {
            this(null, 0);
        }
    }

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Id
    private int version;

    @TenantId
    @Column(name = "workspace_id")
    private UUID workspaceId;

    private String content;

    @Column(name = "author_id")
    private UUID authorId;

    private String note;

    @Column(name = "created_at")
    private Instant createdAt;

    protected DocumentVersionEntity() {}

    static DocumentVersionEntity from(DocumentVersion v) {
        DocumentVersionEntity e = new DocumentVersionEntity();
        e.documentId = v.documentId();
        e.version = v.version();
        e.content = v.content();
        e.authorId = v.authorId();
        e.note = v.note();
        e.createdAt = v.createdAt();
        return e;
    }

    DocumentVersion toDomain() {
        return new DocumentVersion(documentId, version, content, authorId, note, createdAt);
    }
}
