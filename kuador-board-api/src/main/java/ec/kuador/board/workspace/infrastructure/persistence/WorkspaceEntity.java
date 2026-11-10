package ec.kuador.board.workspace.infrastructure.persistence;

import ec.kuador.board.workspace.domain.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workspace")
class WorkspaceEntity {

    @Id
    UUID id;

    String slug;

    String name;

    String description;

    @Column(name = "brand_color")
    String brandColor;

    @Column(name = "brand_on_color")
    String brandOnColor;

    String logo;

    @Column(name = "catalog_version", insertable = false, updatable = false)
    int catalogVersion;

    @Column(name = "created_at")
    Instant createdAt;

    protected WorkspaceEntity() {}

    static WorkspaceEntity from(Workspace w) {
        WorkspaceEntity e = new WorkspaceEntity();
        e.id = w.id();
        e.slug = w.slug();
        e.name = w.name();
        e.description = w.description();
        e.brandColor = w.brandColor();
        e.brandOnColor = w.brandOnColor();
        e.logo = w.logo();
        e.createdAt = w.createdAt();
        return e;
    }

    Workspace toDomain() {
        return new Workspace(id, slug, name, description, brandColor, brandOnColor, logo, createdAt);
    }
}
