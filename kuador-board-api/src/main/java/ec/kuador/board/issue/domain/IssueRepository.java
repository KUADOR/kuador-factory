package ec.kuador.board.issue.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puerto de salida: persistencia de issues y comentarios. */
public interface IssueRepository {

    Optional<Issue> findByKey(String key);

    boolean existsByKey(String key);

    /** Issues del proyecto ordenados por posición (el orden de columnas lo pone quien conoce el flujo). */
    List<Issue> findByProject(String projectKey, IssueFilter filter);

    /** Issues del proyecto que NO están en esas columnas (las de trabajo en curso), por posición. */
    default List<Issue> findOutside(String projectKey, List<String> statuses) {
        throw new UnsupportedOperationException();
    }

    /** Los últimos que cambiaron en una columna, del más reciente al más antiguo. */
    default List<Issue> findLatestInColumn(String projectKey, String status, int limit) {
        throw new UnsupportedOperationException();
    }

    /** Cuántos issues hay en cada columna del proyecto. */
    default java.util.Map<String, Long> countByStatus(String projectKey) {
        throw new UnsupportedOperationException();
    }

    /** Por épica: cuántas de sus tareas hay ({@code [1]}) y cuántas están en esas columnas de cierre ({@code [0]}). */
    default java.util.Map<String, long[]> epicProgress(String projectKey, List<String> doneStatuses) {
        throw new UnsupportedOperationException();
    }

    /** Títulos de esas tarjetas, por clave. */
    default java.util.Map<String, String> titles(java.util.Collection<String> keys) {
        throw new UnsupportedOperationException();
    }

    /** La vista de lista: filtrada, ordenada y paginada en la base. */
    default IssuePage search(String projectKey, IssueQuery query) {
        throw new UnsupportedOperationException();
    }

    /** Issues de una columna ordenados por posición. */
    List<Issue> findColumn(String projectKey, String status);

    /** Issues que supervisa una persona (en todos los proyectos del espacio). */
    List<Issue> findBySupervisor(UUID supervisorId);

    int nextPosition(String projectKey, String status);

    /** Hijos de una épica, en orden de creación. */
    List<Issue> findChildren(String parentKey);

    long countInColumn(String projectKey, String status);

    Issue save(Issue issue);

    void saveAll(List<Issue> issues);

    void delete(Issue issue);

    List<Comment> findComments(Issue issue);

    Comment saveComment(Comment comment);

    java.util.Optional<Comment> findComment(java.util.UUID commentId);

    void deleteComment(java.util.UUID commentId);
}
