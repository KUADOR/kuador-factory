package ec.kuador.board.sync.infrastructure.persistence;

import ec.kuador.board.sync.domain.ComponentRepository;
import ec.kuador.board.sync.domain.ProjectComponent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Entity
@Table(name = "project_component")
class ComponentEntity {

    @Id
    private UUID id;
    @TenantId
    @Column(name = "workspace_id")
    private UUID workspaceId;
    @Column(name = "project_key")
    private String projectKey;
    private String name;
    @Column(name = "template_key")
    private String templateKey;
    @Column(name = "template_version")
    private Long templateVersion;
    @Column(name = "template_repo")
    private String templateRepo;
    @Column(name = "template_ref")
    private String templateRef;
    private String kind;
    private String stack;
    @Column(name = "build_command")
    private String buildCommand;
    @Column(name = "test_command")
    private String testCommand;
    @Column(name = "run_command")
    private String runCommand;
    @Column(name = "repo_location")
    private String repoLocation;
    private String path;
    @Column(name = "installation_id")
    private Long installationId;
    @Column(name = "created_by")
    private UUID createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    private String notes;

    protected ComponentEntity() {}

    static ComponentEntity from(ProjectComponent c) {
        ComponentEntity e = new ComponentEntity();
        e.id = c.id();
        e.projectKey = c.projectKey();
        e.name = c.name();
        e.templateKey = c.templateKey();
        e.templateVersion = c.templateVersion();
        e.templateRepo = c.templateRepo();
        e.templateRef = c.templateRef();
        e.kind = c.kind();
        e.stack = c.stack();
        e.buildCommand = c.buildCommand();
        e.testCommand = c.testCommand();
        e.runCommand = c.runCommand();
        e.repoLocation = c.repoLocation();
        e.path = c.path();
        e.installationId = c.installationId();
        e.createdBy = c.createdBy();
        e.createdAt = c.createdAt();
        e.notes = c.notes();
        return e;
    }

    ProjectComponent toDomain() {
        return new ProjectComponent(id, projectKey, name, templateKey, templateVersion, templateRepo, templateRef, kind, stack, buildCommand,
                testCommand, runCommand, repoLocation, path, installationId, createdBy, createdAt, notes);
    }
}

interface SpringDataComponents extends JpaRepository<ComponentEntity, UUID> {

    List<ComponentEntity> findByProjectKeyOrderByCreatedAtAsc(String projectKey);

    long countByTemplateKey(String templateKey);
}

@Repository
class JpaComponentRepository implements ComponentRepository {

    private final SpringDataComponents jpa;

    JpaComponentRepository(SpringDataComponents jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<ProjectComponent> findByProject(String projectKey) {
        return jpa.findByProjectKeyOrderByCreatedAtAsc(projectKey).stream().map(ComponentEntity::toDomain).toList();
    }

    @Override
    public long countByTemplate(String templateKey) {
        return jpa.countByTemplateKey(templateKey);
    }

    @Override
    public void save(ProjectComponent component) {
        jpa.save(ComponentEntity.from(component));
    }
}
