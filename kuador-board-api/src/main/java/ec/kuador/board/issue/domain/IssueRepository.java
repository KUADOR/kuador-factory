package ec.kuador.board.issue.domain;

import java.util.List;
import java.util.Optional;

/** Puerto de salida: persistencia de issues y comentarios. */
public interface IssueRepository {

    Optional<Issue> findByKey(String key);

    boolean existsByKey(String key);

    /** Issues del proyecto ordenados por posición (el orden de columnas lo pone quien conoce el flujo). */
    List<Issue> findByProject(String projectKey, IssueFilter filter);

    /** Issues de una columna ordenados por posición. */
    List<Issue> findColumn(String projectKey, String status);

    int nextPosition(String projectKey, String status);

    long countInColumn(String projectKey, String status);

    Issue save(Issue issue);

    void saveAll(List<Issue> issues);

    void delete(Issue issue);

    List<Comment> findComments(Issue issue);

    Comment saveComment(Comment comment);
}
