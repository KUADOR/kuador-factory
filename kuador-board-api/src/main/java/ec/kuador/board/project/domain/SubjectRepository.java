package ec.kuador.board.project.domain;

import java.util.List;
import java.util.Optional;

/** Puerto de salida: los proyectos del espacio actual y qué tableros los atienden. */
public interface SubjectRepository {

    List<Subject> findAll();

    Optional<Subject> findByKey(String key);

    void insert(Subject subject);

    void updateDetails(String key, String name, String description);

    void delete(String key);

    /** Los proyectos que atiende el tablero, en su orden. */
    List<Subject> ofBoard(String projectKey);

    /** Los tableros que atienden el proyecto. */
    List<String> boardsOf(String subjectKey);

    /** Suma el proyecto al final de los del tablero (si ya estaba, no hace nada). */
    void link(String projectKey, String subjectKey);

    void unlink(String projectKey, String subjectKey);
}
