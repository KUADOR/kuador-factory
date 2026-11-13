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

    /**
     * Si {@code memberId} puede ver el tablero: los agentes y quien administra el espacio, todos; quien edita o
     * solo mira, los tableros donde está.
     */
    boolean canAccess(java.util.UUID memberId, String projectKey);

    /** Los tableros donde está una persona (sin contar los que ve por su nivel). */
    java.util.Set<String> boardsOf(java.util.UUID memberId);

    /** Suma una persona del espacio al tablero. */
    void addPerson(String projectKey, java.util.UUID memberId);

    /** Reserva el siguiente número de issue del proyecto de forma atómica. */
    int allocateIssueNumber(String projectKey);
}
