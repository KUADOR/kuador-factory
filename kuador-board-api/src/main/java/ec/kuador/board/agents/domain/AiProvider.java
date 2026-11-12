package ec.kuador.board.agents.domain;

import java.util.List;

/**
 * Un proveedor de IA para los agentes del tablero, configurado por quien administra la plataforma: su API, el
 * protocolo con el que se habla y los modelos que se ofrecen.
 */
public record AiProvider(String code, String name, Protocol protocol, String baseUrl, List<String> models, String keyHint, boolean active, int sortOrder) {

    public enum Protocol {
        /** API de Claude: ejecución de código y archivos de Anthropic. */
        ANTHROPIC,
        /** Chat Completions con funciones (OpenAI, DeepSeek y compatibles): las herramientas de datos las pone el tablero. */
        OPENAI_COMPATIBLE
    }

    public AiProvider {
        models = List.copyOf(models);
    }

    public boolean offers(String model) {
        return model != null && models.contains(model);
    }
}
