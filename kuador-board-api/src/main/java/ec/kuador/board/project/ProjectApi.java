package ec.kuador.board.project;

import java.util.Optional;

/** API pública del módulo para el resto de la aplicación (espacio de trabajo actual). */
public interface ProjectApi {

    boolean exists(String projectKey);

    Optional<ProjectView> find(String projectKey);

    /** Nombre visible del proyecto (o su clave si no existe). */
    String name(String projectKey);

    /** Reserva el siguiente número de issue del proyecto de forma atómica. */
    int allocateIssueNumber(String projectKey);
}
