package ec.kuador.board.issue.infrastructure.persistence;

import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.issue.domain.Priority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "issue")
class IssueEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "issue_key")
    private String key;

    @Column(name = "project_key")
    private String projectKey;

    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    private IssueType type;

    private String status;

    @Enumerated(EnumType.STRING)
    private Priority priority;

    @Column(name = "story_points")
    private Integer storyPoints;

    /** JSON {clave: valor} de los datos propios del tipo de tarjeta. */
    @Column(name = "custom_fields")
    private String customFields;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "reporter_id")
    private UUID reporterId;

    @Column(name = "parent_key")
    private String parentKey;

    @Column(name = "subject_key")
    private String subjectKey;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> labels = new ArrayList<>();

    private int position;

    @Column(name = "rework_count")
    private int reworkCount;


    @Column(name = "supervisor_id")

    private UUID supervisorId;

    @Column(name = "work_role")
    private String workRole;

    @Column(name = "review_by")
    private String reviewBy;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "depends_on", columnDefinition = "text[]")
    private List<String> dependsOn = new ArrayList<>();

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private long version;

    protected IssueEntity() {}

    static IssueEntity newFrom(Issue issue) {
        IssueEntity e = new IssueEntity();
        e.id = issue.id();
        e.key = issue.key();
        e.projectKey = issue.projectKey();
        e.reporterId = issue.reporterId();
        e.createdAt = issue.createdAt();
        e.copyFrom(issue);
        return e;
    }

    void copyFrom(Issue issue) {
        title = issue.title();
        description = issue.description();
        type = issue.type();
        status = issue.status();
        priority = issue.priority();
        storyPoints = issue.storyPoints();
        customFields = issue.fields().isEmpty() ? null : ec.kuador.board.shared.json.Json.write(issue.fields());
        assigneeId = issue.assigneeId();
        parentKey = issue.parentKey();
        subjectKey = issue.subjectKey();
        labels = new ArrayList<>(issue.labels());
        position = issue.position();
        reworkCount = issue.reworkCount();
        supervisorId = issue.supervisorId();
        workRole = issue.workRole();
        reviewBy = issue.reviewBy();
        dependsOn = new ArrayList<>(issue.dependsOn());
        updatedAt = issue.updatedAt();
    }

    Issue toDomain() {
        Issue issue = new Issue(id, key, projectKey, title, description, type, status, priority, storyPoints, assigneeId,
                reporterId, parentKey, labels == null ? List.of() : List.copyOf(labels), position, reworkCount, createdAt, updatedAt);
        issue.superviseBy(supervisorId);
        issue.subject(subjectKey);
        if (customFields != null && !customFields.isBlank()) issue.fields(ec.kuador.board.shared.json.Json.readMap(customFields));
        issue.reviewBy(reviewBy);
        if (parentKey != null && (workRole != null || (dependsOn != null && !dependsOn.isEmpty()))) {
            issue.planAsTask(workRole, dependsOn == null ? List.of() : List.copyOf(dependsOn));
        }
        return issue;
    }

    UUID id() {
        return id;
    }
}
