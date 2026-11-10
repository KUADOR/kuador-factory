package ec.kuador.board.catalog.infrastructure.persistence;

import ec.kuador.board.catalog.domain.CatalogType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "catalog_entry")
class CatalogEntryEntity {

    @Id
    UUID id;

    @TenantId
    @Column(name = "workspace_id")
    UUID workspaceId;

    @Enumerated(EnumType.STRING)
    CatalogType type;

    @Column(name = "entry_key")
    String key;

    String payload;

    @Column(name = "built_in")
    boolean builtIn;

    @Column(name = "created_at")
    Instant createdAt;

    @Column(name = "updated_at")
    Instant updatedAt;

    @Version
    long version;

    protected CatalogEntryEntity() {}

    CatalogEntryEntity(CatalogType type, String key, Instant now) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.key = key;
        this.createdAt = now;
    }
}
