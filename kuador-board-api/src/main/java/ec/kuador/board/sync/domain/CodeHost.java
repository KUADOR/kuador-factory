package ec.kuador.board.sync.domain;

import ec.kuador.board.sync.CodeReviewApi.PullDetail;
import ec.kuador.board.sync.CodeReviewApi.PullSummary;
import java.util.List;
import java.util.Optional;

/** Puerto: lectura de Pull Requests, CI y archivos de un repositorio alojado (hoy GitHub). */
public interface CodeHost {

    /**
     * Cómo llegar a un repo. {@code kind} GITHUB: {@code secret} es el token cifrado; GITHUB_APP: el número de la
     * conexión de GitHub del espacio.
     */
    record Access(String repository, String kind, String secret) {}

    boolean supports(String kind);

    /** Los Pull Requests más recientes (abiertos y cerrados). */
    List<PullSummary> pulls(Access access);

    Optional<PullDetail> pull(Access access, int number);

    Optional<String> file(Access access, String ref, String path);

    /** Las rutas de archivos del repo en {@code ref} (nulo = su rama principal), hasta {@code max}. */
    default List<String> tree(Access access, String ref, int max) {
        return List.of();
    }

    /**
     * Fusiona el PR solo si su último commit sigue siendo {@code headSha}: si llegó otro después de revisarlo, GitHub lo
     * rechaza. Usa el método que el repo permite (squash si está habilitado; si no, merge commit; si no, rebase): así la
     * regla vive en la configuración del repo y vale igual para quien fusiona en GitHub. Devuelve el commit resultante.
     */
    String merge(Access access, int number, String headSha, String title);

    /**
     * Si el PR se puede fusionar sin conflictos con su rama destino: vacío mientras GitHub todavía lo calcula (o si el
     * host no lo informa), falso si hay conflictos.
     */
    default Optional<Boolean> mergeable(Access access, int number) {
        return Optional.empty();
    }
}
