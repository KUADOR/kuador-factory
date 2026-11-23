package ec.kuador.board.catalog;

/**
 * Algo que la tarjeta debe tener para salir de una etapa: un archivo adjunto, un documento de cierto tipo o un dato
 * propio lleno. Lo comprueba el tablero al entregar o aprobar, sea quien sea (persona o agente).
 * {@code ref}: el tipo de documento ({@link Kind#DOCUMENT}) o la clave del dato ({@link Kind#FIELD}); nulo en adjuntos.
 */
public record Requirement(Kind kind, String ref) {

    public enum Kind {
        /** Al menos un archivo adjunto en la tarjeta. */
        ATTACHMENT,
        /** Un documento de la tarjeta del tipo {@code ref} (p. ej. SPEC, PROPUESTA). */
        DOCUMENT,
        /** El dato propio {@code ref} de la tarjeta tiene valor (p. ej. monto). */
        FIELD
    }

    public Requirement {
        ref = ref == null || ref.isBlank() ? null : ref.strip();
    }
}
