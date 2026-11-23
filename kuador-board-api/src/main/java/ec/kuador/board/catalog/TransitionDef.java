package ec.kuador.board.catalog;

/**
 * Un camino del flujo en una plantilla (de una columna a otra, por clave). {@code when}: la condición en palabras del
 * negocio («si todo coincide», «si falta información»); con ella es un camino según el resultado, que elige quien entrega.
 */
public record TransitionDef(String from, String to,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String when) {

    public TransitionDef {
        when = when == null || when.isBlank() ? null : when.strip();
    }

    public TransitionDef(String from, String to) {
        this(from, to, null);
    }
}
