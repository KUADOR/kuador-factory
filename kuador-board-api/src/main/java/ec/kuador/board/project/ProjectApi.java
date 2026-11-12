package ec.kuador.board.project;

import java.util.Optional;

/** API pública del módulo para el resto de la aplicación (espacio de trabajo actual). */
public interface ProjectApi {

    boolean exists(String projectKey);

    Optional<ProjectView> find(String projectKey);

    /** Nombre visible del proyecto (o su clave si no existe). */
    String name(String projectKey);

    /** Crea un tablero desde una plantilla del catálogo del espacio (p. ej. el tablero inicial de un paquete). */
    void createBoard(String key, String name, String templateKey);

    /** Reserva el siguiente número de issue del proyecto de forma atómica. */
    int allocateIssueNumber(String projectKey);
}
