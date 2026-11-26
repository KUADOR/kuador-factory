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

    /** Un proyecto en un tablero, con el manual de trabajo con que ese tablero lo trabaja (nulo = sin elegir). */
    record Linked(Subject subject, String methodology) {}

    /** Los proyectos que atiende el tablero, en su orden. */
    List<Linked> ofBoard(String projectKey);

    /** Los tableros que atienden el proyecto. */
    List<String> boardsOf(String subjectKey);

    /** Suma el proyecto al final de los del tablero, con ese manual de trabajo (si ya estaba, no hace nada). */
    void link(String projectKey, String subjectKey, String methodology);

    void setMethodology(String projectKey, String subjectKey, String methodology);

    /** En cuántos tableros se trabaja algún proyecto con este manual. */
    long countUsingMethodology(String methodology);

    void unlink(String projectKey, String subjectKey);
}
