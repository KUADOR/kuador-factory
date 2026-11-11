package ec.kuador.board.sync;

import java.util.List;

/** API pública: de qué piezas está hecho un proyecto y dónde vive cada una (para agentes y kits). */
public interface RepositoriesApi {

    /**
     * Una pieza del proyecto. {@code path} es la carpeta dentro del repo ("" = la raíz, en un repo por
     * pieza); los comandos vienen de su plantilla.
     */
    record Piece(String name, String kind, String stack, String template, String repository, String path, String build, String test, String run) {}

    List<Piece> pieces(String projectKey);
}
