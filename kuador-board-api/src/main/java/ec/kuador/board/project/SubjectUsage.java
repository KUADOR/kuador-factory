package ec.kuador.board.project;

/**
 * Puerto que implementa el módulo issue: las tarjetas de un proyecto en un tablero. Permite impedir que se quite del
 * tablero un proyecto con trabajo sin que project dependa de issue.
 */
public interface SubjectUsage {

    long countIssues(String projectKey, String subjectKey);
}
