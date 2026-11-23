package ec.kuador.board.catalog;

/** En qué se mide el tamaño de una tarjeta. El número se guarda igual; cambia cómo se llama y si se muestra. */
public enum EstimateUnit {
    /** Puntos de historia (lo de siempre en software). */
    POINTS,
    /** Horas de trabajo. */
    HOURS,
    /** El tablero no estima: el dato no se muestra ni lo piden los agentes. */
    NONE
}
