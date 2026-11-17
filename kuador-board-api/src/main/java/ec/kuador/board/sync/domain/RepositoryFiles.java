package ec.kuador.board.sync.domain;

import java.util.List;
import java.util.Optional;

/**
 * Puerto: archivos Markdown de un repositorio, vistos desde su rama principal. Las rutas son
 * relativas a la raíz y con "/". Cada adaptador garantiza que ninguna ruta salga del repositorio.
 */
public interface RepositoryFiles {

    Optional<String> read(String path);

    /** Archivos .md bajo {@code directory} (relativo; "" = raíz) hasta {@code depth} niveles. */
    List<String> listMarkdown(String directory, int depth);

    record FileWrite(String path, String content) {}

    /** Resultado de publicar: el Pull Request que una persona revisa y fusiona (sin número si se actualizó uno abierto). */
    record Published(Integer pullRequest, String url, String branch) {}

    /**
     * Publica los archivos. En GitHub abre un PR, o agrega un commit a {@code existingBranch} si ya
     * hay un PR del tablero abierto.
     */
    Published publish(List<FileWrite> files, String commitMessage, String title, String description, String existingBranch);

    enum PullRequestState {
        OPEN,
        MERGED,
        CLOSED
    }

    /** Estado de un PR; vacío si el repositorio no usa PRs o el PR ya no existe. */
    Optional<PullRequestState> pullRequestState(int number);
}
