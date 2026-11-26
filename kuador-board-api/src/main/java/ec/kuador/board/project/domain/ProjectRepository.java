package ec.kuador.board.project.domain;

import ec.kuador.board.catalog.ColumnDef;
import java.util.List;
import java.util.Optional;

/** Puerto de salida: proyectos y sus columnas, del espacio actual. */
public interface ProjectRepository {

    List<Project> findAll();

    Optional<Project> findByKey(String key);

    /** Borra el tablero; la base borra en cascada columnas, tarjetas, comentarios, documentos y conexiones. */
    void delete(String key);

    boolean existsByKey(String key);

    /** Inserta un proyecto nuevo. */
    Project save(Project project);

    void updateMethodology(String projectKey, String methodology);

    void updateDetails(String projectKey, String name, String description);

    void updateTransitions(String projectKey, ec.kuador.board.project.Transitions transitions);

    void updateStages(String projectKey, java.util.List<ec.kuador.board.catalog.StageDef> stages, int maxRework);

    void updateCard(String projectKey, java.util.List<ec.kuador.board.catalog.IssueTypeDef> issueTypes, ec.kuador.board.catalog.EstimateUnit estimate);

    void updateCapabilities(String projectKey, java.util.List<ec.kuador.board.catalog.Capability> capabilities);

    long countUsingMethodology(String methodology);

    default long countFromTemplate(String templateKey) {
        return 0;
    }

    List<ColumnDef> findColumns(String projectKey);

    /** Reemplaza todas las columnas del proyecto por {@code columns}, en ese orden. */
    void saveColumns(String projectKey, List<ColumnDef> columns);

    /** Incrementa el contador del proyecto y devuelve el número reservado, o vacío si no existe. */
    Optional<Integer> nextIssueNumber(String key);
}
