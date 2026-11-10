package ec.kuador.board.activity.infrastructure.persistence;

import ec.kuador.board.activity.domain.ActivityEntry;
import ec.kuador.board.activity.domain.ActivityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "activity")
class ActivityEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "issue_key")
    private String issueKey;

    @Column(name = "project_key")
    private String projectKey;

    @Column(name = "actor_id")
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    private ActivityType type;

    private String field;

    @Column(name = "from_value")
    private String from;

    @Column(name = "to_value")
    private String to;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    protected ActivityEntity() {}

    static ActivityEntity from(ActivityEntry a) {
        ActivityEntity e = new ActivityEntity();
        e.id = a.id();
        e.eventId = a.eventId();
        e.issueKey = a.issueKey();
        e.projectKey = a.projectKey();
        e.actorId = a.actorId();
        e.type = a.type();
        e.field = a.field();
        e.from = a.from();
        e.to = a.to();
        e.occurredAt = a.occurredAt();
        return e;
    }

    ActivityEntry toDomain() {
        return new ActivityEntry(id, eventId, issueKey, projectKey, actorId, type, field, from, to, occurredAt);
    }
}
