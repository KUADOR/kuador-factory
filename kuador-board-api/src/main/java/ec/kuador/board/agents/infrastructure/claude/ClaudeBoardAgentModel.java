package ec.kuador.board.agents.infrastructure.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.MultipartField;
import com.anthropic.core.http.HttpResponse;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.files.FileMetadata;
import com.anthropic.models.files.FileUploadParams;
import com.anthropic.models.messages.CodeExecutionTool20260120;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import ec.kuador.board.agents.domain.AgentModelException;
import ec.kuador.board.agents.domain.AiProvider;
import ec.kuador.board.agents.domain.BoardAgentModel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Un agente del tablero sobre la API de Claude, con la clave de la empresa. Sube los adjuntos de la tarjeta al
 * entorno de ejecución de código (Files API + container_upload), deja que el modelo calcule y genere archivos, y
 * termina cuando el modelo llama a {@code deliver} o {@code ask_human}. Los archivos que guardó en $OUTPUT_DIR se
 * descargan para adjuntarlos. Al final borra de Anthropic los archivos subidos y los generados.
 */
@Component
class ClaudeBoardAgentModel implements BoardAgentModel {

    private static final Logger log = LoggerFactory.getLogger(ClaudeBoardAgentModel.class);
    /** Vueltas máximas (pausas largas del servidor y llamadas a herramientas) antes de rendirse. */
    private static final int MAX_TURNS = 12;
    private static final long MAX_OUTPUT_BYTES = 30L * 1024 * 1024;

    /** Cómo es su entorno: se suma a las reglas del tablero. */
    private static final String ENVIRONMENT = """

            Tu entorno:
            - Puedes ejecutar código (Python y bash) con pandas, openpyxl, matplotlib y reportlab, sin Internet (para leer un PDF, revisa qué librería hay instalada). Los archivos de la \
            tarjeta ya están ahí; si no los ves en el directorio actual, búscalos por nombre con find.
            - Para cálculos sobre datos usa código, no cuentas de cabeza.
            - Los archivos que entregas (PDF, Excel, CSV, imágenes) van copiados a $OUTPUT_DIR con un nombre claro; en el mismo comando lista \
            $OUTPUT_DIR para confirmar que quedaron.
            """;

    /** Para pruebas: si está, manda sobre la dirección del proveedor. */
    private final String overrideUrl;

    ClaudeBoardAgentModel(@Value("${board.agents.anthropic-base-url:}") String overrideUrl) {
        this.overrideUrl = overrideUrl == null ? "" : overrideUrl.strip();
    }

    @Override
    public AiProvider.Protocol protocol() {
        return AiProvider.Protocol.ANTHROPIC;
    }

    private AnthropicClient client(Endpoint endpoint) {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().apiKey(endpoint.apiKey().strip()).timeout(Duration.ofMinutes(10)).maxRetries(2);
        String url = !overrideUrl.isEmpty() ? overrideUrl : endpoint.baseUrl();
        if (url != null && !url.isBlank()) b.baseUrl(url.strip());
        return b.build();
    }

    @Override
    public void checkKey(Endpoint endpoint) {
        AnthropicClient client = client(endpoint);
        try {
            client.models().list();
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new AgentModelException("Anthropic rechazó la clave: revisa que esté completa y activa.", e);
        } catch (AnthropicException e) {
            throw new AgentModelException("No se pudo comprobar la clave con Anthropic: " + e.getMessage(), e);
        } finally {
            client.close();
        }
    }

    @Override
    public Result work(Endpoint endpoint, String model, Task task) {
        AnthropicClient client = client(endpoint);
        List<String> uploaded = new ArrayList<>();
        Set<String> produced = new LinkedHashSet<>();
        try {
            List<ContentBlockParam> first = new ArrayList<>();
            first.add(ContentBlockParam.ofText(task.user()));
            for (InputFile f : task.files()) {
                FileMetadata meta = client.files().upload(FileUploadParams.builder()
                        .file(MultipartField.<InputStream>builder().value(new ByteArrayInputStream(f.content())).filename(f.name())
                                .contentType(f.contentType()).build())
                        .build());
                uploaded.add(meta.id());
                first.add(ContentBlockParam.ofContainerUpload(meta.id()));
            }
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(32_000L)
                    .system(task.system() + ENVIRONMENT)
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .addTool(CodeExecutionTool20260120.builder().build())
                    .addTool(tool("deliver", "Entrega el trabajo terminado. Los archivos para la persona deben estar ya copiados en $OUTPUT_DIR.",
                            Map.of("summary", "Resumen para la persona: qué hiciste, los números más importantes y qué archivos adjuntaste."), "summary"))
                    .addTool(tool("ask_human", "Pregunta a una persona algo que falta y sin lo cual no puedes hacer bien el trabajo. Detiene el trabajo hasta que responda.",
                            Map.of("question", "La pregunta, concreta y con las opciones si las hay."), "question"))
                    .addUserMessageOfContentBlockParams(first);
            String usedModel = model;
            long input = 0;
            long output = 0;
            for (int turn = 0; turn < MAX_TURNS; turn++) {
                Message response = call(client, params.build());
                usedModel = response.model().asString();
                input += response.usage().inputTokens();
                output += response.usage().outputTokens();
                collectFiles(response, produced);
                response.container().ifPresent(c -> params.container(c.id()));
                StopReason stop = response.stopReason().orElse(null);
                if (StopReason.REFUSAL.equals(stop)) throw new AgentModelException("El modelo declinó el pedido.");
                for (ContentBlock block : response.content()) {
                    if (block.toolUse().isEmpty()) continue;
                    ToolUseBlock use = block.toolUse().get();
                    Map<String, Object> args = use._input().convert(new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    if (use.name().equals("deliver")) {
                        return new Result(Outcome.DELIVERED, text(args.get("summary")), download(client, produced), usedModel, input, output);
                    }
                    if (use.name().equals("ask_human")) {
                        return new Result(Outcome.QUESTION, text(args.get("question")), download(client, produced), usedModel, input, output);
                    }
                }
                if (StopReason.PAUSE_TURN.equals(stop)) {
                    // El servidor pausó un turno largo (ejecución de código): se le devuelve tal cual para que siga.
                    params.addMessage(response);
                    continue;
                }
                if (StopReason.MAX_TOKENS.equals(stop)) throw new AgentModelException("La respuesta se cortó por longitud antes de terminar.");
                // Terminó sin llamar a deliver: se toma su último texto como entrega.
                String last = response.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).reduce((a, b) -> b).orElse("").strip();
                if (last.isEmpty()) throw new AgentModelException("El modelo terminó sin entregar nada.");
                return new Result(Outcome.DELIVERED, last, download(client, produced), usedModel, input, output);
            }
            throw new AgentModelException("El agente no terminó en " + MAX_TURNS + " vueltas; divide el pedido o acláralo.");
        } finally {
            for (String id : uploaded) forget(client, id);
            for (String id : produced) forget(client, id);
            client.close();
        }
    }

    private static Message call(AnthropicClient client, MessageCreateParams params) {
        try {
            return client.messages().create(params);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new AgentModelException("Anthropic rechazó la clave de IA del espacio (Conexiones → IA).", e);
        } catch (RateLimitException e) {
            throw new AgentModelException("Se alcanzó el límite de uso de la cuenta de Anthropic; reintenta en unos minutos.", e);
        } catch (AnthropicServiceException e) {
            throw new AgentModelException("La API de Anthropic respondió " + e.statusCode() + ": " + e.getMessage(), e);
        } catch (AnthropicException e) {
            throw new AgentModelException("No se pudo hablar con la API de Anthropic: " + e.getMessage(), e);
        }
    }

    /** Ids de los archivos que dejó en $OUTPUT_DIR (ejecuciones de bash o de Python). */
    private static void collectFiles(Message response, Set<String> into) {
        for (ContentBlock block : response.content()) {
            block.bashCodeExecutionToolResult().ifPresent(r -> r.content().bashCodeExecutionResultBlock()
                    .ifPresent(res -> res.content().forEach(o -> into.add(o.fileId()))));
            block.codeExecutionToolResult().ifPresent(r -> r.content().resultBlock()
                    .ifPresent(res -> res.content().forEach(o -> into.add(o.fileId()))));
        }
    }

    private static List<OutputFile> download(AnthropicClient client, Set<String> ids) {
        List<OutputFile> files = new ArrayList<>();
        long total = 0;
        for (String id : ids) {
            FileMetadata meta = client.files().retrieveMetadata(id);
            if (total + meta.sizeBytes() > MAX_OUTPUT_BYTES) {
                log.warn("Archivo generado {} omitido: se superó el tamaño total", meta.filename());
                continue;
            }
            try (HttpResponse res = client.files().download(id); InputStream in = res.body()) {
                byte[] content = in.readAllBytes();
                total += content.length;
                files.add(new OutputFile(meta.filename(), meta.mimeType(), content));
            } catch (IOException e) {
                throw new AgentModelException("No se pudo descargar el archivo generado " + meta.filename() + ".", e);
            }
        }
        return files;
    }

    private static void forget(AnthropicClient client, String id) {
        try {
            client.files().delete(id);
        } catch (RuntimeException e) {
            log.debug("No se pudo borrar el archivo {} de Anthropic: {}", id, e.getMessage());
        }
    }

    private static Tool tool(String name, String description, Map<String, String> fields, String required) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        fields.forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(Map.of("type", "string", "description", v))));
        return Tool.builder().name(name).description(description)
                .inputSchema(Tool.InputSchema.builder().properties(props.build()).addRequired(required).build()).build();
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString().strip();
    }
}
