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

    /** Si un agente la está trabajando ahora (una ejecución abierta o el préstamo de una máquina): no se borra. */
    default boolean agentWorking(String issueKey) {
        return false;
    }

    /** Si algún documento de la tarjeta tiene un diagrama Mermaid. */
    default boolean hasDiagram(String issueKey) {
        return false;
    }

    /** Si la tarjeta ya tiene el documento que escribe ese rol según la metodología del tablero (vacío: el rol no escribe ninguno). */
    default java.util.Optional<Boolean> hasDocumentOf(String projectKey, String issueKey, String role) {
        return java.util.Optional.empty();
    }
}
