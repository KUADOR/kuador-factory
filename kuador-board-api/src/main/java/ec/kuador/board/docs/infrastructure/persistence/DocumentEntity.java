package ec.kuador.board.docs.infrastructure.persistence;

import ec.kuador.board.docs.domain.Document;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "document")
class DocumentEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "project_key")
    private String projectKey;

    @Column(name = "subject_key")
    private String subjectKey;

    @Column(name = "issue_key")
    private String issueKey;

    private String kind;

    private String title;

    private String path;

    @Column(name = "current_version")
    private int currentVersion;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /** Bloqueo optimista: dos guardados simultáneos sobre la misma versión no pueden ganar ambos. */
    @Version
    private long version;

    protected DocumentEntity() {}

    static DocumentEntity newFrom(Document d) {
        DocumentEntity e = new DocumentEntity();
        e.id = d.id();
        e.projectKey = d.projectKey();
        e.subjectKey = d.subjectKey();
        e.issueKey = d.issueKey();
        e.kind = d.kind();
        e.createdAt = d.createdAt();
        e.copyFrom(d);
        return e;
    }

    void copyFrom(Document d) {
        title = d.title();
        path = d.path();
        currentVersion = d.currentVersion();
        updatedAt = d.updatedAt();
        updatedBy = d.updatedBy();
    }

    Document toDomain() {
        return new Document(id, projectKey, subjectKey, issueKey, kind, title, path, currentVersion, createdAt, updatedAt, updatedBy);
    }
}
