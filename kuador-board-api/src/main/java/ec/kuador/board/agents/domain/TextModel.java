package ec.kuador.board.agents.domain;

/**
 * Puerto: un pedido de texto a un proveedor de IA, sin herramientas (redactar, preguntar, pulir). Cada proveedor tiene
 * su implementación según su protocolo. La respuesta trae el consumo, para el gasto y los frenos de presupuesto.
 */
public interface TextModel {

    record Reply(String text, String model, long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {}

    AiProvider.Protocol protocol();

    Reply complete(BoardAgentModel.Endpoint endpoint, String model, String system, String user, int maxTokens);
}
