package ec.kuador.board.project;

import java.util.Optional;

/** API pública del módulo para el resto de la aplicación (espacio de trabajo actual). */
public interface ProjectApi {

    boolean exists(String projectKey);

    Optional<ProjectView> find(String projectKey);

    /** Las claves de los tableros que puede ver quien pide (su alcance de tableros). */
    java.util.List<String> visibleKeys();

    /** Nombre visible del proyecto (o su clave si no existe). */
    String name(String projectKey);

    /** El objetivo del tablero (para qué existe, qué le importa al negocio); vacío si no lo escribieron. */
    default java.util.Optional<String> goal(String projectKey) {
        return java.util.Optional.empty();
    }

    /** Guarda el objetivo del tablero (vacío lo borra). */
    default void saveGoal(String projectKey, String goal) {
        throw new UnsupportedOperationException("saveGoal");
    }

    /** Crea un tablero desde una plantilla del catálogo del espacio (p. ej. el tablero inicial de un paquete). */
    void createBoard(String key, String name, String templateKey);

    /**
     * Si {@code memberId} puede ver el tablero: los agentes y quien administra el espacio, todos; quien edita o
     * solo mira, los tableros donde está.
     */
    boolean canAccess(java.util.UUID memberId, String projectKey);

    /** Los tableros donde está una persona (sin contar los que ve por su nivel). */
    java.util.Set<String> boardsOf(java.util.UUID memberId);

    /** Los tableros de un agente por la API (con su token): los de su equipo, no todo el espacio. */
    java.util.Set<String> agentBoardsOf(java.util.UUID agentId);

    /** Suma una persona del espacio al tablero. */
    void addPerson(String projectKey, java.util.UUID memberId);

    /**
     * Los proyectos que atiende el tablero (aquello sobre lo que se trabaja: un sistema, una marca), en su orden. El
     * primero es el que se propone al crear una tarjeta. Solo queda vacío si el tablero no existe.
     */
    default java.util.List<SubjectView> subjectsOf(String projectKey) {
        return find(projectKey).map(p -> java.util.List.of(new SubjectView(p.key(), p.name(), null))).orElse(java.util.List.of());
    }

    /**
     * El proyecto de una tarjeta del tablero: {@code wanted} si el tablero lo atiende; si no (o si viene vacío), el
     * primero del tablero.
     */
    default String subjectKey(String projectKey, String wanted) {
        java.util.List<SubjectView> all = subjectsOf(projectKey);
        if (wanted != null && all.stream().anyMatch(s -> s.key().equals(wanted))) return wanted;
        return all.isEmpty() ? projectKey : all.getFirst().key();
    }

    /** El proyecto de una tarjeta, con su nombre y descripción (vacío si el tablero no existe). */
    default java.util.Optional<SubjectView> subject(String projectKey, String subjectKey) {
        String key = subjectKey(projectKey, subjectKey);
        return subjectsOf(projectKey).stream().filter(s -> s.key().equals(key)).findFirst();
    }

    /** Reserva el siguiente número de issue del proyecto de forma atómica. */
    int allocateIssueNumber(String projectKey);
}
