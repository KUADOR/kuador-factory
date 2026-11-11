package ec.kuador.board.sync.domain;

/**
 * Puerto: un tipo de repositorio (LOCAL, GITHUB con token, GITHUB_APP con la app de la plataforma).
 * Valida vínculos y abre sus archivos.
 */
public interface RepositoryProvider {

    /**
     * Lo que una persona escribió al vincular. {@code token} solo para GITHUB; {@code installationId}
     * (la conexión de GitHub del espacio) solo para GITHUB_APP.
     */
    record LinkSpec(String location, String branch, String token, Long installationId) {

        public LinkSpec(String location, String branch, String token) {
            this(location, branch, token, null);
        }
    }

    /** Ubicación normalizada, rama efectiva y secreto ya cifrado (o nulo). */
    record Validated(String location, String branch, String secret) {}

    String kind();

    /** Valida y normaliza; lanza DomainException con un mensaje claro si no es usable. */
    Validated validate(LinkSpec spec);

    RepositoryFiles open(RepositoryLink link);
}
