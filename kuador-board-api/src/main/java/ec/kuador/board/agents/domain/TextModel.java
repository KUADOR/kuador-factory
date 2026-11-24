package ec.kuador.board.agents.domain;

/**
 * Puerto: un pedido de texto a un proveedor de IA, sin herramientas (redactar, preguntar, pulir). Cada proveedor tiene
 * su implementación según su protocolo. La respuesta trae el consumo, para el gasto y los frenos de presupuesto.
 */
public interface TextModel {

    /**
     * {@code truncated}: el modelo llegó al tope de tokens y el texto quedó a medias. Cada uso decide: un documento a
     * medias no sirve, pero una respuesta del chat se puede mostrar con un aviso (y el gasto igual se registra).
     */
    record Reply(String text, String model, long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens, boolean truncated) {

        public Reply(String text, String model, long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
            this(text, model, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, false);
        }
    }

    /** Lo que se dice cuando un texto que tiene que llegar entero (un documento, una propuesta) se cortó. */
    String CUT_MESSAGE = "La respuesta del modelo se cortó por largo: pide documentos más cortos o de a uno.";

    AiProvider.Protocol protocol();

    Reply complete(BoardAgentModel.Endpoint endpoint, String model, String system, String user, int maxTokens);
}
