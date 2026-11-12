package ec.kuador.board.agents.domain;

/** Puerto: el modelo de lenguaje que redacta la propuesta del Agente PO. */
public interface ProductOwnerModel {

    record Result(PoProposal proposal, String model, long inputTokens, long outputTokens) {}

    /** Falso si no hay credenciales: los agentes quedan desactivados y se registra por qué. */
    boolean available();

    String unavailableReason();

    /** Con la clave del servidor. Lanza {@link AgentModelException} si el modelo falla, declina o la respuesta no sirve. */
    Result propose(PoContext context);

    /** Con la clave de IA de la empresa. */
    Result propose(String apiKey, PoContext context);
}
