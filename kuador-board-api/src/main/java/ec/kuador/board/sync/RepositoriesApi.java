package ec.kuador.board.sync;

import java.util.List;

/** API pública: de qué piezas está hecho un proyecto y dónde vive cada una (para agentes y kits). */
public interface RepositoriesApi {

    /**
     * Una pieza del proyecto. {@code path} es la carpeta dentro del repo ("" = la raíz, en un repo por
     * pieza); los comandos vienen de su plantilla y el equipo los puede ajustar. {@code notes}: lo que el equipo quiere
     * que los agentes sepan de la pieza (versiones, convenciones), o nulo.
     */
    record Piece(String name, String kind, String stack, String template, String repository, String path, String build, String test, String run,
            String notes) {}

    List<Piece> pieces(String projectKey);
}
