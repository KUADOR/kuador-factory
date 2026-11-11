package ec.kuador.board.sync.domain;

import java.util.List;

/** Puerto de persistencia de las piezas (espacio actual). */
public interface ComponentRepository {

    List<ProjectComponent> findByProject(String projectKey);

    void save(ProjectComponent component);
}
