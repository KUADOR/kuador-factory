package ec.kuador.board.sync.domain;

import java.util.List;

/** Puerto: crea un repositorio con sus bases (commit inicial, ramas y protecciones). */
public interface RepositoryCreator {

    /** {@code requiredChecks}: verificaciones de CI que exigen las ramas protegidas (las de sus plantillas). */
    record NewRepository(long installationId, String name, String description, boolean privateRepo, List<String> requiredChecks) {}

    /**
     * Un archivo a escribir. {@code base64}: el contenido es binario codificado (imágenes, jars...);
     * {@code executable}: se marca ejecutable (p. ej. mvnw).
     */
    record File(String path, String content, boolean base64, boolean executable) {

        public File(String path, String content) {
            this(path, content, false, false);
        }

        public File withPath(String newPath) {
            return new File(newPath, content, base64, executable);
        }
    }

    /**
     * El repo quedó creado. {@code workBranch} es la rama a la que van los PRs (develop); {@code warnings}
     * explica lo que GitHub no permitió (p. ej. proteger ramas privadas en el plan gratuito).
     */
    record Created(String fullName, String htmlUrl, String workBranch, List<String> protectedBranches, List<String> warnings) {}

    Created create(NewRepository repo, List<File> files, String commitMessage);

    /** Un Pull Request abierto por el tablero. */
    record Proposal(int number, String url, String branch) {}

    /** Propone archivos en un repo existente: rama nueva desde {@code baseBranch}, un commit y un Pull Request. */
    Proposal propose(long installationId, String repository, String baseBranch, String branch, List<File> files, String title, String body);
}
