package ec.kuador.board.agents.domain;

import java.util.List;

/**
 * Puerto: el modelo con el que trabaja un agente en el tablero, de un proveedor de IA. Recibe el pedido y los archivos
 * de la tarjeta, trabaja con las herramientas que tenga (ejecución de código en Claude; lectura de archivos, consultas
 * y documentos del tablero en los demás) y termina entregando (con archivos) o preguntando a una persona.
 */
public interface BoardAgentModel {

    record InputFile(String name, String contentType, byte[] content) {}

    record OutputFile(String name, String contentType, byte[] content) {}

    /** {@code system}: quién es el agente y las reglas del tablero; cada proveedor suma cómo es su entorno. */
    record Task(String system, String user, List<InputFile> files) {}

    /** A dónde y con qué clave se habla. */
    record Endpoint(String baseUrl, String apiKey) {}

    enum Outcome {
        DELIVERED,
        QUESTION
    }

    /** {@code text}: el resumen de la entrega o la pregunta para la persona. */
    record Result(Outcome outcome, String text, List<OutputFile> files, String model, long inputTokens, long outputTokens) {}

    /** El protocolo que habla este adaptador. */
    AiProvider.Protocol protocol();

    /** Comprueba que la clave sirve; lanza {@link AgentModelException} con un motivo claro si no. */
    void checkKey(Endpoint endpoint);

    /** Lanza {@link AgentModelException} si el modelo falla, declina o no llega a entregar. */
    Result work(Endpoint endpoint, String model, Task task);
}
