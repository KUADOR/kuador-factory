package ec.kuador.board.sync;

import java.util.List;

/**
 * API pública: leer el código de un proyecto en GitHub sin clonarlo, para que un agente del tablero (p. ej. QA) revise
 * una entrega: los Pull Requests, sus cambios, el resultado del CI y archivos del repo. Solo lectura y solo los
 * repositorios vinculados al proyecto (el de documentos y los de sus piezas).
 */
public interface CodeReviewApi {

    record PullSummary(String repository, int number, String title, String branch, String baseBranch, String state, boolean merged, String author,
            String url, String body, String updatedAt) {}

    /** {@code patch}: el diff del archivo (recortado si es largo; nulo en binarios o diffs enormes). */
    record ChangedFile(String path, String status, int additions, int deletions, String patch) {}

    /** Un check o estado del CI sobre el último commit. {@code conclusion}: success, failure… (nula mientras corre). */
    record Check(String name, String status, String conclusion, String summary) {}

    /** {@code checksNote}: por qué no se pudo leer el CI (p. ej. falta el permiso de la app), o nula. */
    /**
     * {@code filesTruncated}: algún diff se recortó o se omitió por tamaño. {@code filesIncomplete}: la lista de archivos
     * no está completa (el PR tiene más de los que se leyeron).
     */
    record PullDetail(PullSummary pull, String headSha, List<ChangedFile> files, boolean filesTruncated, List<Check> checks, String checksNote,
            boolean filesIncomplete) {
        public PullDetail(PullSummary pull, String headSha, List<ChangedFile> files, boolean filesTruncated, List<Check> checks, String checksNote) {
            this(pull, headSha, files, filesTruncated, checks, checksNote, false);
        }
    }

    /** Repositorios de GitHub del proyecto que se pueden leer ("owner/repo"). */
    List<String> repositories(String projectKey);

    /**
     * Pull Requests de los repos del proyecto, los más recientes primero. {@code search} (p. ej. la clave del issue)
     * filtra por título, rama o descripción; vacío = los últimos.
     */
    List<PullSummary> pullRequests(String projectKey, String search);

    /** Un Pull Request con sus cambios y el CI de su último commit. DomainException si el repo no es del proyecto. */
    PullDetail pullRequest(String projectKey, String repository, int number);

    /** Un archivo de texto del repo en una rama o commit. DomainException si no existe o no es del proyecto. */
    String file(String projectKey, String repository, String ref, String path);

    /** Fusiona el PR si su último commit sigue siendo {@code headSha}; devuelve el commit en la rama principal. */
    String merge(String projectKey, String repository, int number, String headSha, String title);
}
