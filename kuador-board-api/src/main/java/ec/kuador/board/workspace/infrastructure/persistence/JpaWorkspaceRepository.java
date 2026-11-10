package ec.kuador.board.workspace.infrastructure.persistence;

import ec.kuador.board.workspace.domain.Workspace;
import ec.kuador.board.workspace.domain.WorkspaceRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

interface SpringDataWorkspaces extends JpaRepository<WorkspaceEntity, UUID> {

    boolean existsBySlug(String slug);

    @org.springframework.data.jpa.repository.Query("select w.id from WorkspaceEntity w order by w.createdAt")
    java.util.List<UUID> findAllIds();

    @org.springframework.data.jpa.repository.Query("select w.catalogVersion from WorkspaceEntity w where w.id = :id")
    int findCatalogVersion(UUID id);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update WorkspaceEntity w set w.catalogVersion = :version where w.id = :id")
    int updateCatalogVersion(UUID id, int version);
}

@Repository
class JpaWorkspaceRepository implements WorkspaceRepository {

    private final SpringDataWorkspaces jpa;

    JpaWorkspaceRepository(SpringDataWorkspaces jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Workspace> findById(UUID id) {
        return jpa.findById(id).map(WorkspaceEntity::toDomain);
    }

    @Override
    public boolean existsBySlug(String slug) {
        return jpa.existsBySlug(slug);
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public java.util.List<UUID> allIds() {
        return jpa.findAllIds();
    }

    @Override
    public int catalogVersion(UUID workspaceId) {
        return jpa.findCatalogVersion(workspaceId);
    }

    @Override
    public void setCatalogVersion(UUID workspaceId, int version) {
        jpa.updateCatalogVersion(workspaceId, version);
    }

    @Override
    public Workspace save(Workspace workspace) {
        return jpa.save(WorkspaceEntity.from(workspace)).toDomain();
    }
}
