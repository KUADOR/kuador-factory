package ec.kuador.board.catalog;

/** Un paso permitido del flujo en una plantilla (de una columna a otra, por clave). */
public record TransitionDef(String from, String to) {}
