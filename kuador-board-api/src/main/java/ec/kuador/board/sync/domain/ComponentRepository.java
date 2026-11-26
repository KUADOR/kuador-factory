package ec.kuador.board.sync.domain;

import java.util.List;

/** Puerto de persistencia de las piezas (espacio actual). */
public interface ComponentRepository {

    /** Las piezas de un proyecto en un tablero. */
    List<ProjectComponent> findByProject(String projectKey, String subjectKey);

    /** Las piezas de todos los proyectos del tablero. */
    List<ProjectComponent> findByBoard(String projectKey);

    /** Cuántas piezas salieron de esta plantilla (para no borrar una en uso). */
    default long countByTemplate(String templateKey) {
        return 0;
    }

    void save(ProjectComponent component, String subjectKey);
}
