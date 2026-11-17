package ec.kuador.board.agents.domain;

/** Puerto: el modelo de lenguaje que redacta la propuesta del Agente PO. */
public interface ProductOwnerModel {

    record Result(PoProposal proposal, String model, long inputTokens, long outputTokens) {}

    /**
     * Con la clave de IA de la empresa (no hay clave del servidor). Lanza {@link AgentModelException} si el modelo
     * falla, declina o la respuesta no sirve.
     */
    Result propose(String apiKey, PoContext context);

    /** Con la clave de la empresa y el modelo elegido para el agente ({@code null} = el configurado). */
    default Result propose(String apiKey, String model, PoContext context) {
        return propose(apiKey, context);
    }

    /** Además, cuánto razona ({@code null} = el configurado). */
    default Result propose(String apiKey, String model, String effort, PoContext context) {
        return propose(apiKey, model, context);
    }
}
