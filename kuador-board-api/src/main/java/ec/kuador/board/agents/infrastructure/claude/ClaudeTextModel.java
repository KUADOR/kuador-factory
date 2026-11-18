package ec.kuador.board.agents.infrastructure.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import ec.kuador.board.agents.domain.AgentModelException;
import ec.kuador.board.agents.domain.AiProvider;
import ec.kuador.board.agents.domain.BoardAgentModel;
import ec.kuador.board.agents.domain.TextModel;
import java.time.Duration;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Texto con Claude, en streaming (las respuestas largas no se cortan por tiempo). */
@Component
class ClaudeTextModel implements TextModel {

    private final String overrideUrl;

    ClaudeTextModel(@Value("${board.agents.anthropic-base-url:}") String overrideUrl) {
        this.overrideUrl = overrideUrl == null ? "" : overrideUrl.strip();
    }

    @Override
    public AiProvider.Protocol protocol() {
        return AiProvider.Protocol.ANTHROPIC;
    }

    @Override
    public Reply complete(BoardAgentModel.Endpoint endpoint, String model, String system, String user, int maxTokens) {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().apiKey(endpoint.apiKey().strip()).timeout(Duration.ofMinutes(5)).maxRetries(1);
        String url = !overrideUrl.isEmpty() ? overrideUrl : endpoint.baseUrl();
        if (url != null && !url.isBlank()) b.baseUrl(url.strip());
        AnthropicClient client = b.build();
        try {
            MessageCreateParams params = MessageCreateParams.builder().model(model).maxTokens(maxTokens).system(system).addUserMessage(user).build();
            MessageAccumulator acc = MessageAccumulator.create();
            try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
                stream.stream().forEach(acc::accumulate);
            }
            Message m = acc.message();
            if (StopReason.REFUSAL.equals(m.stopReason().orElse(null))) throw new AgentModelException("El modelo declinó la solicitud.");
            if (StopReason.MAX_TOKENS.equals(m.stopReason().orElse(null))) {
                throw new AgentModelException("La respuesta del modelo se cortó por largo: pide documentos más cortos o de a uno.");
            }
            String text = m.content().stream().flatMap(c -> c.text().stream()).map(t -> t.text()).collect(Collectors.joining());
            return new Reply(text, m.model().asString(), m.usage().inputTokens(), m.usage().outputTokens(), m.usage().cacheReadInputTokens().orElse(0L),
                    m.usage().cacheCreationInputTokens().orElse(0L));
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new AgentModelException("Anthropic rechazó la clave de IA del espacio (Conexiones → IA).", e);
        } catch (RateLimitException e) {
            throw new AgentModelException("Límite de uso de la API de Anthropic alcanzado; reintenta en unos minutos.", e);
        } catch (AnthropicServiceException e) {
            throw new AgentModelException("La API de Anthropic respondió " + e.statusCode() + ".", e);
        } catch (AnthropicException e) {
            throw new AgentModelException("No se pudo hablar con la API de Anthropic: " + e.getMessage(), e);
        } finally {
            client.close();
        }
    }
}
