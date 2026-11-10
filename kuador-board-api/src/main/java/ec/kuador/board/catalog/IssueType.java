package ec.kuador.board.catalog;

/**
 * Tipos base de issue. La semántica es fija (EPIC agrupa a los demás); cada plantilla elige cuáles
 * usa y cómo se llaman (p. ej. en ventas TASK se muestra como «Oportunidad»).
 */
public enum IssueType {
    EPIC,
    STORY,
    TASK,
    BUG
}
