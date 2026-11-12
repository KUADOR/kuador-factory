package ec.kuador.board.agents.infrastructure.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import ec.kuador.board.agents.domain.AgentModelException;
import ec.kuador.board.agents.domain.PoContext;
import ec.kuador.board.agents.domain.PoPrompt;
import ec.kuador.board.agents.domain.PoProposal;
import ec.kuador.board.agents.domain.ProductOwnerModel;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * El Agente PO sobre la API de Claude: una sola llamada con salida estructurada (la propuesta llega
 * como JSON validado contra {@link PoProposal}). Sin ANTHROPIC_API_KEY los agentes quedan
 * desactivados. Con {@code refusal-fallback}, si el modelo declina por sus clasificadores de
 * seguridad, el servidor reintenta con un modelo alternativo (beta server-side-fallback).
 */
@Component
class ClaudeProductOwnerModel implements ProductOwnerModel {

    private final AnthropicClient client;
    private final String baseUrl;
    private final String model;
    private final OutputConfig.Effort effort;
    private final boolean refusalFallback;

    ClaudeProductOwnerModel(
            @Value("${board.agents.anthropic-api-key:}") String apiKey,
            @Value("${board.agents.model:claude-opus-5-5}") String model,
            @Value("${board.agents.effort:medium}") String effort,
            @Value("${board.agents.refusal-fallback:true}") boolean refusalFallback,
            // Solo para pruebas contra una API falsa; en producción queda vacío (api.anthropic.com).
            @Value("${board.agents.anthropic-base-url:}") String baseUrl) {
        AnthropicOkHttpClient.Builder client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey.strip())
                .timeout(Duration.ofMinutes(10))
                .maxRetries(2);
        if (!baseUrl.isBlank()) client.baseUrl(baseUrl.strip());
        this.client = apiKey.isBlank() ? null : client.build();
        this.baseUrl = baseUrl.strip();
        this.model = model;
        this.effort = OutputConfig.Effort.of(effort.strip().toLowerCase());
        this.refusalFallback = refusalFallback;
    }

    @Override
    public boolean available() {
        return client != null;
    }

    @Override
    public String unavailableReason() {
        return "Los agentes están desactivados: falta ANTHROPIC_API_KEY en la configuración de la API.";
    }

    @Override
    public Result propose(String apiKey, PoContext context) {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().apiKey(apiKey.strip()).timeout(Duration.ofMinutes(10)).maxRetries(2);
        if (!baseUrl.isEmpty()) b.baseUrl(baseUrl);
        AnthropicClient own = b.build();
        try {
            return propose(own, context);
        } finally {
            own.close();
        }
    }

    @Override
    public Result propose(PoContext context) {
        if (client == null) throw new AgentModelException(unavailableReason());
        return propose(client, context);
    }

    private Result propose(AnthropicClient client, PoContext context) {
        StructuredMessageCreateParams.Builder<PoProposal> builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16_000L)
                .system(PoPrompt.system(context))
                .addUserMessage(PoPrompt.user(context))
                .outputConfig(StructuredOutputConfig.<PoProposal>builder()
                        .format(PoProposal.class, JsonSchemaLocalValidation.YES)
                        .effort(effort)
                        .build());
        if (refusalFallback) {
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        StructuredMessage<PoProposal> response;
        try {
            response = client.messages().create(builder.build());
        } catch (UnauthorizedException e) {
            throw new AgentModelException("Anthropic rechazó la clave (ANTHROPIC_API_KEY inválida o revocada).", e);
        } catch (RateLimitException e) {
            throw new AgentModelException("Límite de uso de la API de Anthropic alcanzado; reintenta en unos minutos.", e);
        } catch (AnthropicServiceException e) {
            throw new AgentModelException("La API de Anthropic respondió " + e.statusCode() + ": " + e.getMessage(), e);
        } catch (AnthropicException e) {
            throw new AgentModelException("No se pudo hablar con la API de Anthropic: " + e.getMessage(), e);
        }

        StopReason stop = response.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) {
            String category = response.stopDetails().flatMap(d -> d.category().map(Object::toString)).orElse("sin categoría");
            throw new AgentModelException("El modelo declinó la solicitud (categoría: " + category + ").");
        }
        if (StopReason.MAX_TOKENS.equals(stop)) {
            throw new AgentModelException("La respuesta del modelo se cortó por longitud; el issue o sus documentos son demasiado extensos.");
        }
        PoProposal proposal = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .findFirst()
                .orElseThrow(() -> new AgentModelException("El modelo no devolvió una propuesta."));
        return new Result(proposal, response.model().asString(), response.usage().inputTokens(), response.usage().outputTokens());
    }
}
