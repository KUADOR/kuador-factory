package ec.kuador.board.project;

/**
 * Puerto que implementa el módulo issue: cuántos issues hay en una columna. Permite impedir que se
 * borre una columna con trabajo sin que project dependa de issue (issue ya depende de project).
 */
public interface ColumnUsage {

    long countIssues(String projectKey, String columnKey);
}
