package ec.kuador.board.issue;

/**
 * Puerto: lo que la tarjeta tiene y vive en otros módulos (adjuntos, documentos). El tablero lo consulta para saber si
 * una tarjeta cumple lo que su etapa exige antes de avanzar. Cada módulo responde lo que sabe y deja el resto en falso.
 */
public interface CardEvidence {

    /** Si la tarjeta tiene al menos un archivo adjunto. */
    default boolean hasAttachment(String issueKey) {
        return false;
    }

    /** Si la tarjeta tiene un documento de ese tipo. */
    default boolean hasDocument(String issueKey, String kind) {
        return false;
    }
}
