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
}
