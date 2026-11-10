package ec.kuador.board.sync.domain;

/** Puerto: un tipo de repositorio (LOCAL, GITHUB). Valida vínculos y abre sus archivos. */
public interface RepositoryProvider {

    /** Lo que una persona escribió al vincular. {@code token} solo para GitHub. */
    record LinkSpec(String location, String branch, String token) {}

    /** Ubicación normalizada, rama efectiva y secreto ya cifrado (o nulo). */
    record Validated(String location, String branch, String secret) {}

    String kind();

    /** Valida y normaliza; lanza DomainException con un mensaje claro si no es usable. */
    Validated validate(LinkSpec spec);

    RepositoryFiles open(RepositoryLink link);
}
