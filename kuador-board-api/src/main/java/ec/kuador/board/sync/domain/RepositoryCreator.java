package ec.kuador.board.sync.domain;

import java.util.List;

/** Puerto: crea un repositorio con sus bases (commit inicial, ramas y protecciones). */
public interface RepositoryCreator {

    record NewRepository(long installationId, String name, String description, boolean privateRepo) {}

    /** Un archivo de texto del commit inicial. */
    record File(String path, String content) {}

    /**
     * El repo quedó creado. {@code workBranch} es la rama a la que van los PRs (develop); {@code warnings}
     * explica lo que GitHub no permitió (p. ej. proteger ramas privadas en el plan gratuito).
     */
    record Created(String fullName, String htmlUrl, String workBranch, List<String> protectedBranches, List<String> warnings) {}

    Created create(NewRepository repo, List<File> files, String commitMessage);
}
