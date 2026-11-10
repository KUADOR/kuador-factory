package ec.kuador.board.issue.infrastructure.persistence;

import ec.kuador.board.issue.domain.Comment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "issue_comment")
class CommentEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "issue_id")
    private UUID issueId;

    @Column(name = "author_id")
    private UUID authorId;

    private String body;

    @Column(name = "created_at")
    private Instant createdAt;

    protected CommentEntity() {}

    static CommentEntity from(Comment c) {
        CommentEntity e = new CommentEntity();
        e.id = c.id();
        e.issueId = c.issueId();
        e.authorId = c.authorId();
        e.body = c.body();
        e.createdAt = c.createdAt();
        return e;
    }

    Comment toDomain() {
        return new Comment(id, issueId, authorId, body, createdAt);
    }
}
