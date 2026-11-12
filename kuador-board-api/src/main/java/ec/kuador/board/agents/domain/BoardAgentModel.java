package ec.kuador.board.agents.domain;

import java.util.List;

/**
 * Puerto: el modelo con el que trabaja un agente en el tablero. Recibe el pedido y los archivos de la tarjeta, puede
 * ejecutar código en un entorno aislado (cálculos exactos, gráficos, PDF) y termina entregando (con archivos) o
 * preguntando a una persona.
 */
public interface BoardAgentModel {

    record InputFile(String name, String contentType, byte[] content) {}

    record OutputFile(String name, String contentType, byte[] content) {}

    record Task(String system, String user, List<InputFile> files) {}

    enum Outcome {
        DELIVERED,
        QUESTION
    }

    /** {@code text}: el resumen de la entrega o la pregunta para la persona. */
    record Result(Outcome outcome, String text, List<OutputFile> files, String model, long inputTokens, long outputTokens) {}

    /** Comprueba que la clave sirve; lanza {@link AgentModelException} con un motivo claro si no. */
    void checkKey(String apiKey);

    /** Lanza {@link AgentModelException} si el modelo falla, declina o no llega a entregar. */
    Result work(String apiKey, String model, Task task);
}
