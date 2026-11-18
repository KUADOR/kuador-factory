package ec.kuador.board.sync;

import java.util.List;
import java.util.UUID;

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

    /** Una pieza que ya existe en un repo: la descubrió el reconocimiento y la aprobó una persona. */
    record ExistingPiece(String name, String kind, String stack, String repository, String path, String build, String test, String run, String notes) {}

    /** Registra (o actualiza por nombre) las piezas de un proyecto existente, sin crear ni tocar repos. */
    default void registerExisting(String projectKey, List<ExistingPiece> pieces, UUID actorId) {
        throw new UnsupportedOperationException("registerExisting");
    }

    /** Propone el kit de agentes a los repos del proyecto por Pull Request (nunca directo a la rama principal); devuelve los enlaces. */
    default List<String> proposeKit(String projectKey, UUID actorId) {
        return List.of();
    }
}
