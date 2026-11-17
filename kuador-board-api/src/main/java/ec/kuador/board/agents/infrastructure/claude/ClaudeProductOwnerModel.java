package ec.kuador.board.agents.infrastructure.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
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
 * como JSON validado contra {@link PoProposal}), siempre con la clave de IA del espacio (no hay clave del
 * servidor). Con {@code refusal-fallback}, si el modelo declina por sus clasificadores de
 * seguridad, el servidor reintenta con un modelo alternativo (beta server-side-fallback).
 * <p>
 * El modelo siempre piensa y ese razonamiento cuenta dentro de {@code max_tokens}, junto con el documento
 * completo (spec o seguimiento) que va en la propuesta: por eso el tope es alto y la llamada va en streaming,
 * que no corre el riesgo de que la conexión HTTP venza en una respuesta larga.
 */
@Component
class ClaudeProductOwnerModel implements ProductOwnerModel {

    private final String baseUrl;
    private final String model;
    private final OutputConfig.Effort effort;
    private final boolean refusalFallback;

    /** Razonamiento + propuesta + documento Markdown completo. */
    static final long MAX_TOKENS = 64_000L;

    ClaudeProductOwnerModel(
            @Value("${board.agents.model:claude-opus-5-5}") String model,
            @Value("${board.agents.effort:medium}") String effort,
            @Value("${board.agents.refusal-fallback:true}") boolean refusalFallback,
            // Solo para pruebas contra una API falsa; en producción queda vacío (api.anthropic.com).
            @Value("${board.agents.anthropic-base-url:}") String baseUrl) {
        this.baseUrl = baseUrl.strip();
        this.model = model;
        this.effort = OutputConfig.Effort.of(effort.strip().toLowerCase());
        this.refusalFallback = refusalFallback;
    }

    @Override
    public Result propose(String apiKey, PoContext context) {
        return propose(apiKey, null, context);
    }

    @Override
    public Result propose(String apiKey, String chosenModel, PoContext context) {
        return propose(apiKey, chosenModel, null, context);
    }

    @Override
    public Result propose(String apiKey, String chosenModel, String chosenEffort, PoContext context) {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().apiKey(apiKey.strip()).timeout(Duration.ofMinutes(10)).maxRetries(2);
        if (!baseUrl.isEmpty()) b.baseUrl(baseUrl);
        AnthropicClient own = b.build();
        try {
            OutputConfig.Effort e = chosenEffort == null || chosenEffort.isBlank() ? effort : OutputConfig.Effort.of(chosenEffort.strip());
            return propose(own, chosenModel == null || chosenModel.isBlank() ? model : chosenModel, e, context);
        } finally {
            own.close();
        }
    }

    private Result propose(AnthropicClient client, String model, OutputConfig.Effort effort, PoContext context) {
        StructuredMessageCreateParams.Builder<PoProposal> builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
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
        MessageAccumulator accumulator = MessageAccumulator.create();
        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(builder.build())) {
            stream.stream().forEach(accumulator::accumulate);
            response = accumulator.message(PoProposal.class);
        } catch (UnauthorizedException e) {
            throw new AgentModelException("Anthropic rechazó la clave de IA del espacio (inválida o revocada): revísala en Conexiones → IA.", e);
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
            throw new AgentModelException("La propuesta del modelo pasó de " + MAX_TOKENS + " tokens (razonamiento y documento) y se cortó; "
                    + "divide el issue en partes más chicas o acota lo que pide.");
        }
        PoProposal proposal = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .findFirst()
                .orElseThrow(() -> new AgentModelException("El modelo no devolvió una propuesta."));
        return new Result(proposal, response.model().asString(), response.usage().inputTokens(), response.usage().outputTokens());
    }
}
