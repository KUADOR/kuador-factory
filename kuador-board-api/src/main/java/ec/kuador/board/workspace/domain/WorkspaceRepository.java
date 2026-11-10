package ec.kuador.board.workspace.domain;

import java.util.Optional;
import java.util.UUID;

/** Puerto: espacios de trabajo (tabla global, sin filtro por espacio). */
public interface WorkspaceRepository {

    Optional<Workspace> findById(UUID id);

    boolean existsBySlug(String slug);

    long count();

    Workspace save(Workspace workspace);

    /** Todos los espacios (para tareas de mantenimiento al arrancar). */
    java.util.List<UUID> allIds();

    int catalogVersion(UUID workspaceId);

    void setCatalogVersion(UUID workspaceId, int version);
}
