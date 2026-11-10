package ec.kuador.board.sync.infrastructure.persistence;

import ec.kuador.board.sync.domain.ExportPullRequest;
import ec.kuador.board.sync.domain.RepositoryFiles.PullRequestState;
import ec.kuador.board.sync.domain.RepositoryLink;
import ec.kuador.board.sync.domain.SyncRecord;
import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "project_repository")
class RepositoryLinkEntity {

    @Id
    UUID id;

    @TenantId
    @Column(name = "workspace_id")
    UUID workspaceId;

    @Column(name = "project_key")
    String projectKey;

    String kind;

    String location;

    String branch;

    /** Token cifrado (AES-GCM); nunca se expone. */
    String secret;

    @Column(name = "linked_at")
    Instant linkedAt;

    @Column(name = "linked_by")
    UUID linkedBy;

    protected RepositoryLinkEntity() {}

    static RepositoryLinkEntity from(RepositoryLink l) {
        RepositoryLinkEntity e = new RepositoryLinkEntity();
        e.id = UUID.randomUUID();
        e.projectKey = l.projectKey();
        e.kind = l.kind();
        e.location = l.location();
        e.branch = l.branch();
        e.secret = l.secret();
        e.linkedAt = l.linkedAt();
        e.linkedBy = l.linkedBy();
        return e;
    }

    RepositoryLink toDomain() {
        return new RepositoryLink(projectKey, kind, location, branch, secret, linkedAt, linkedBy);
    }
}

@Entity
@Table(name = "document_sync")
class SyncRecordEntity {

    @Id
    @Column(name = "document_id")
    UUID documentId;

    @TenantId
    @Column(name = "workspace_id")
    UUID workspaceId;

    @Column(name = "synced_version")
    int syncedVersion;

    @Column(name = "file_sha256")
    String fileSha256;

    @Column(name = "synced_at")
    Instant syncedAt;

    protected SyncRecordEntity() {}

    static SyncRecordEntity from(SyncRecord r) {
        SyncRecordEntity e = new SyncRecordEntity();
        e.documentId = r.documentId();
        e.syncedVersion = r.syncedVersion();
        e.fileSha256 = r.fileSha256();
        e.syncedAt = r.syncedAt();
        return e;
    }

    SyncRecord toDomain() {
        return new SyncRecord(documentId, syncedVersion, fileSha256, syncedAt);
    }
}

@Entity
@Table(name = "sync_pull_request")
class PullRequestEntity {

    @Id
    UUID id;

    @TenantId
    @Column(name = "workspace_id")
    UUID workspaceId;

    @Column(name = "project_key")
    String projectKey;

    int number;

    String url;

    String branch;

    @Enumerated(EnumType.STRING)
    PullRequestState state;

    @Column(name = "created_at")
    Instant createdAt;

    @Column(name = "created_by")
    UUID createdBy;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sync_pull_request_item", joinColumns = @JoinColumn(name = "pull_request_id"))
    List<PullRequestItem> items = new ArrayList<>();

    protected PullRequestEntity() {}

    static PullRequestEntity from(ExportPullRequest pr) {
        PullRequestEntity e = new PullRequestEntity();
        e.id = pr.id();
        e.projectKey = pr.projectKey();
        e.number = pr.number();
        e.url = pr.url();
        e.branch = pr.branch();
        e.state = pr.state();
        e.createdAt = pr.createdAt();
        e.createdBy = pr.createdBy();
        e.items = new ArrayList<>(pr.items().stream().map(i -> new PullRequestItem(i.documentId(), i.version(), i.contentSha256())).toList());
        return e;
    }

    ExportPullRequest toDomain() {
        return new ExportPullRequest(id, projectKey, number, url, branch, state, createdAt, createdBy,
                items.stream().map(i -> new ExportPullRequest.Item(i.documentId, i.version, i.contentSha256)).toList());
    }
}

@Embeddable
class PullRequestItem {

    @Column(name = "document_id")
    UUID documentId;

    int version;

    @Column(name = "content_sha256")
    String contentSha256;

    protected PullRequestItem() {}

    PullRequestItem(UUID documentId, int version, String contentSha256) {
        this.documentId = documentId;
        this.version = version;
        this.contentSha256 = contentSha256;
    }
}
