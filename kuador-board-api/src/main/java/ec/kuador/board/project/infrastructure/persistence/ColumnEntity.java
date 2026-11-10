package ec.kuador.board.project.infrastructure.persistence;

import ec.kuador.board.catalog.ColumnCategory;
import ec.kuador.board.catalog.ColumnDef;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "board_column")
class ColumnEntity {

    @Id
    UUID id;

    @TenantId
    @Column(name = "workspace_id")
    UUID workspaceId;

    @Column(name = "project_key")
    String projectKey;

    @Column(name = "column_key")
    String key;

    String name;

    @Enumerated(EnumType.STRING)
    ColumnCategory category;

    int position;

    protected ColumnEntity() {}

    static ColumnEntity from(String projectKey, ColumnDef c, int position) {
        ColumnEntity e = new ColumnEntity();
        e.id = UUID.randomUUID();
        e.projectKey = projectKey;
        e.key = c.key();
        e.name = c.name();
        e.category = c.category();
        e.position = position;
        return e;
    }

    ColumnDef toDomain() {
        return new ColumnDef(key, name, category);
    }
}
