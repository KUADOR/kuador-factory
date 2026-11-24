package ec.kuador.board.agents.infrastructure.openai;

import ec.kuador.board.agents.domain.AgentModelException;
import ec.kuador.board.agents.domain.AiProvider;
import ec.kuador.board.agents.domain.BoardAgentModel;
import ec.kuador.board.agents.domain.TextModel;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Texto con un proveedor compatible con OpenAI (DeepSeek, Qwen, OpenRouter…). */
@Component
class OpenAiCompatibleTextModel implements TextModel {

    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    OpenAiCompatibleTextModel(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public AiProvider.Protocol protocol() {
        return AiProvider.Protocol.OPENAI_COMPATIBLE;
    }

    @Override
    public Reply complete(BoardAgentModel.Endpoint endpoint, String model, String system, String user, int maxTokens) {
        ObjectNode body = json.createObjectNode().put("model", model).put("max_tokens", maxTokens);
        body.putArray("messages").add(json.createObjectNode().put("role", "system").put("content", system))
                .add(json.createObjectNode().put("role", "user").put("content", user));
        String base = endpoint.baseUrl().strip().replaceAll("/+$", "");
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/chat/completions")).timeout(Duration.ofMinutes(5))
                .header("Authorization", "Bearer " + endpoint.apiKey().strip()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AgentModelException("No se pudo hablar con el proveedor de IA: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentModelException("Se interrumpió el pedido al proveedor de IA.", e);
        }
        if (res.statusCode() == 401 || res.statusCode() == 403) throw new AgentModelException("El proveedor rechazó la clave de IA del espacio (Conexiones → IA).");
        if (res.statusCode() == 429) throw new AgentModelException("Se alcanzó el límite de uso del proveedor de IA; reintenta en unos minutos.");
        if (res.statusCode() >= 300) throw new AgentModelException("El proveedor de IA respondió " + res.statusCode() + ".");
        JsonNode r = json.readTree(res.body());
        JsonNode choice = r.path("choices").path(0);
        boolean cut = "length".equals(choice.path("finish_reason").asString(""));
        return new Reply(choice.path("message").path("content").asString(""), r.path("model").asString(model), r.path("usage").path("prompt_tokens").asLong(0),
                r.path("usage").path("completion_tokens").asLong(0), r.path("usage").path("prompt_tokens_details").path("cached_tokens").asLong(0), 0, cut);
    }
}
