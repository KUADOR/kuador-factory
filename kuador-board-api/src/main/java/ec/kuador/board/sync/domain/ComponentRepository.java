package ec.kuador.board.sync.domain;

import java.util.List;

/** Puerto de persistencia de las piezas (espacio actual). */
public interface ComponentRepository {

    List<ProjectComponent> findByProject(String projectKey);

    /** Cuántas piezas salieron de esta plantilla (para no borrar una en uso). */
    default long countByTemplate(String templateKey) {
        return 0;
    }

    void save(ProjectComponent component);
}
