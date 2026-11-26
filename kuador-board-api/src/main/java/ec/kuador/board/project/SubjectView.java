package ec.kuador.board.project;

/**
 * Un proyecto del espacio: aquello sobre lo que se trabaja (un sistema, una marca, un cliente). El tablero es el equipo
 * y su flujo; un tablero atiende uno o varios proyectos y cada tarjeta pertenece a uno.
 */
public record SubjectView(String key, String name, String description, String methodology) {

    /** {@code methodology}: el manual de trabajo con que el tablero trabaja este proyecto (nulo = sin elegir). */
    public SubjectView {}

    public SubjectView(String key, String name, String description) {
        this(key, name, description, null);
    }
}
