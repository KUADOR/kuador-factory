package ec.kuador.board.project.infrastructure.persistence;

import ec.kuador.board.catalog.IssueTypeDef;
import ec.kuador.board.catalog.ProjectKind;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.project.Transitions;
import ec.kuador.board.project.domain.Project;
import ec.kuador.board.shared.json.Json;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "project")
class ProjectEntity {

    @Id
    UUID id;

    @TenantId
    @Column(name = "workspace_id")
    UUID workspaceId;

    @Column(name = "project_key")
    String key;

    String name;

    String description;

    @Enumerated(EnumType.STRING)
    ProjectKind kind;

    @Column(name = "template_key")
    String templateKey;

    String methodology;

    @Column(name = "issue_types")
    String issueTypes;

    @Column(name = "transitions_restricted")
    boolean transitionsRestricted;

    String transitions;

    String stages;

    @Column(name = "max_rework")
    int maxRework;

    @Column(name = "next_issue_number")
    int nextIssueNumber;

    @Column(name = "created_at")
    Instant createdAt;

    protected ProjectEntity() {}

    static ProjectEntity from(Project p) {
        ProjectEntity e = new ProjectEntity();
        e.id = p.id();
        e.key = p.key();
        e.name = p.name();
        e.description = p.description();
        e.kind = p.kind();
        e.templateKey = p.templateKey();
        e.methodology = p.methodology();
        e.issueTypes = Json.write(p.issueTypes());
        e.transitionsRestricted = p.transitions().restricted();
        e.transitions = Json.write(p.transitions().allowed());
        e.stages = Json.write(p.stages());
        e.maxRework = p.maxRework();
        e.nextIssueNumber = 1;
        e.createdAt = p.createdAt();
        return e;
    }

    Project toDomain() {
        return new Project(id, key, name, description, kind, templateKey, methodology, Json.readList(issueTypes, IssueTypeDef.class),
                new Transitions(transitionsRestricted, Json.readList(transitions, Transitions.Transition.class)),
                Json.readList(stages, StageDef.class), maxRework == 0 ? Project.DEFAULT_MAX_REWORK : maxRework, createdAt);
    }
}
