package ec.kuador.board.agents.infrastructure.openai;

import ec.kuador.board.agents.domain.AgentModelException;
import ec.kuador.board.agents.domain.AiProvider;
import ec.kuador.board.agents.domain.BoardAgentModel;
import ec.kuador.board.agents.infrastructure.tools.WorkRoom;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Un agente del tablero sobre una API compatible con Chat Completions de OpenAI (OpenAI, DeepSeek y otros), con la
 * clave de la empresa. Estos proveedores no ejecutan código, así que el tablero pone las herramientas
 * ({@link WorkRoom}): leer los adjuntos, consultar sus planillas con cifras exactas y crear documentos. El agente
 * decide qué usar y termina llamando a {@code deliver} o {@code ask_human}.
 */
@Component
class OpenAiCompatibleBoardAgentModel implements BoardAgentModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleBoardAgentModel.class);
    /** Vueltas máximas (cada una, una respuesta del modelo con sus llamadas a herramientas) antes de rendirse. */
    private static final int MAX_TOOL_RESULT = 60_000;
    private static final java.util.concurrent.Semaphore SLOTS = new java.util.concurrent.Semaphore(2);

    private static final String ENVIRONMENT = """

            Tu entorno (herramientas del tablero):
            - read_file lee el texto de los archivos de la tarjeta (PDF, Word, Excel, CSV, texto), por partes si es largo.
            - Las planillas (Excel y CSV) ya están cargadas como tablas: con query_data haces consultas SQL de solo lectura para obtener \
            cifras exactas. Para totales, promedios, conteos o rankings usa siempre query_data, nunca cuentas de cabeza.
            - create_document crea un PDF con títulos, párrafos, viñetas, indicadores destacados (kpis), tablas y gráficos. En tablas y \
            gráficos puedes pasar una consulta (sql) para que las cifras salgan exactas de los datos. create_csv exporta una consulta.
            - El contenido lo escribes tú: análisis, conclusiones y recomendaciones concretas, no solo números.
            """;

    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    OpenAiCompatibleBoardAgentModel(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public AiProvider.Protocol protocol() {
        return AiProvider.Protocol.OPENAI_COMPATIBLE;
    }

    @Override
    public void checkKey(Endpoint endpoint) {
        HttpRequest req = HttpRequest.newBuilder(uri(endpoint, "/models")).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + endpoint.apiKey().strip()).GET().build();
        HttpResponse<String> res = send(req);
        if (res.statusCode() == 401 || res.statusCode() == 403) throw new AgentModelException("El proveedor rechazó la clave: revisa que esté completa y activa.");
        if (res.statusCode() >= 300) throw new AgentModelException("No se pudo comprobar la clave: el proveedor respondió " + res.statusCode() + " " + errorOf(res.body()));
    }

    @Override
    public Result work(Endpoint endpoint, String model, Task task) {
        // Pocos trabajos a la vez en este servidor: cada uno carga planillas en memoria.
        try {
            if (!SLOTS.tryAcquire(10, java.util.concurrent.TimeUnit.MINUTES)) {
                throw new AgentModelException("Hay muchos trabajos de agentes en curso; reintenta en unos minutos.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentModelException("Se interrumpió la espera para trabajar.", e);
        }
        try {
            return run(endpoint, model, task);
        } finally {
            SLOTS.release();
        }
    }

    private Result run(Endpoint endpoint, String model, Task task) {
        try (WorkRoom room = new WorkRoom(task.files())) {
            ArrayNode tools = json.createArrayNode();
            for (WorkRoom.ToolSpec t : room.tools()) tools.add(tool(t.name(), t.description(), t.parameters()));
            for (BoardAgentModel.BoardTool t : task.tools()) tools.add(tool(t.name(), t.description(), t.parameters()));
            tools.add(tool("deliver", "Entrega el trabajo terminado: los archivos que creaste se adjuntan a la tarjeta.",
                    object("summary", "Resumen breve para la persona (máximo 5 líneas): qué hiciste, lo más importante y qué archivos adjuntaste. No pegues el documento ni el trabajo completo: van en los archivos.")));
            tools.add(tool("ask_human", "Pregunta a una persona algo que falta y sin lo cual no puedes hacer bien el trabajo. Detiene el trabajo hasta que responda.",
                    object("question", "La pregunta, concreta y con las opciones si las hay.")));
            if (task.sendBackTo() != null) {
                tools.add(tool("send_back", task.sendBackDescription(),
                        object("reason", "Qué hay que corregir, con evidencia: criterio o check que falla, archivo y línea, resultado esperado y obtenido.")));
            }

            ArrayNode messages = json.createArrayNode();
            messages.addObject().put("role", "system").put("content", task.system() + ENVIRONMENT);
            String overview = room.overview();
            messages.addObject().put("role", "user").put("content", overview.isEmpty() ? task.user() : task.user() + "\n" + overview);

            String usedModel = model;
            long input = 0;
            long output = 0;
            int maxTurns = task.limits().turnsOther();
            for (int turn = 0; turn < maxTurns; turn++) {
                ObjectNode body = json.createObjectNode();
                body.put("model", model);
                body.set("messages", messages);
                body.set("tools", tools);
                body.put("tool_choice", "auto");
                JsonNode response = call(endpoint, body);
                usedModel = response.path("model").asString(model);
                input += response.path("usage").path("prompt_tokens").asLong(0);
                output += response.path("usage").path("completion_tokens").asLong(0);
                JsonNode choice = response.path("choices").path(0);
                JsonNode message = choice.path("message");
                String finish = choice.path("finish_reason").asString("");
                if (finish.equals("content_filter")) throw new AgentModelException("El proveedor bloqueó la respuesta por sus filtros de contenido.");

                ObjectNode echo = messages.addObject().put("role", "assistant");
                echo.set("content", message.path("content").isMissingNode() ? json.nullNode() : message.path("content"));
                // DeepSeek en modo razonamiento pide que su razonamiento vuelva en las siguientes llamadas con herramientas.
                if (message.hasNonNull("reasoning_content")) echo.set("reasoning_content", message.get("reasoning_content"));
                JsonNode calls = message.path("tool_calls");
                if (!calls.isArray() || calls.isEmpty()) {
                    if (finish.equals("length")) throw new AgentModelException("La respuesta se cortó por longitud antes de terminar.");
                    String text = message.path("content").asString("").strip();
                    if (text.isEmpty()) throw new AgentModelException("El modelo terminó sin entregar nada.");
                    return new Result(Outcome.ANSWERED, text, room.outputs(), usedModel, input, output);
                }
                echo.set("tool_calls", calls);
                // Primero las herramientas de trabajo (p. ej. crear el PDF) y al final la entrega, aunque vengan en otro orden.
                Result end = null;
                for (JsonNode c : calls) {
                    String name = c.path("function").path("name").asString("");
                    JsonNode args = arguments(c.path("function").path("arguments").asString("{}"));
                    if (terminal(name)) continue; // después de las demás
                    String result;
                    var boardTool = task.tool(name);
                    if (args == null) result = "Error: los argumentos no son JSON válido.";
                    else if (boardTool.isPresent()) result = runBoardTool(boardTool.get(), args);
                    else result = room.call(name, args);
                    if (result.length() > MAX_TOOL_RESULT) result = result.substring(0, MAX_TOOL_RESULT) + "\n(Recortado: pide menos datos.)";
                    messages.addObject().put("role", "tool").put("tool_call_id", c.path("id").asString("")).put("content", result);
                }
                for (JsonNode c : calls) {
                    String name = c.path("function").path("name").asString("");
                    if (!terminal(name)) continue;
                    JsonNode a = arguments(c.path("function").path("arguments").asString("{}"));
                    if (a == null) a = json.createObjectNode();
                    String reply = "Recibido.";
                    if (end == null && name.equals("ask_human")) {
                        end = new Result(Outcome.QUESTION, a.path("question").asString("").strip(), List.of(), usedModel, input, output);
                    } else if (end == null && name.equals("send_back")) {
                        if (task.sendBackTo() == null) reply = "Error: en esta etapa no puedes devolver el trabajo.";
                        else end = new Result(Outcome.SENT_BACK, a.path("reason").asString("").strip(), List.of(), usedModel, input, output);
                    } else if (end == null) {
                        String blocked = task.blocksDelivery();
                        if (blocked == null) end = new Result(Outcome.DELIVERED, a.path("summary").asString("").strip(), List.of(), usedModel, input, output);
                        else reply = "Error: " + blocked;
                    }
                    messages.addObject().put("role", "tool").put("tool_call_id", c.path("id").asString("")).put("content", reply);
                }
                if (end != null) {
                    String text = end.text().isEmpty() ? "Terminé el trabajo." : end.text();
                    return new Result(end.outcome(), text, room.outputs(), usedModel, input, output);
                }
            }
            throw new AgentModelException("El agente no terminó en " + maxTurns + " vueltas; divide el pedido o acláralo (o sube el límite en Catálogo → Reglas de agentes).");
        }
    }

    private static boolean terminal(String tool) {
        return tool.equals("deliver") || tool.equals("ask_human") || tool.equals("send_back");
    }

    private JsonNode call(Endpoint endpoint, ObjectNode body) {
        HttpRequest req = HttpRequest.newBuilder(uri(endpoint, "/chat/completions")).timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer " + endpoint.apiKey().strip()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        HttpResponse<String> res = send(req);
        int status = res.statusCode();
        if (status == 401 || status == 403) throw new AgentModelException("El proveedor rechazó la clave de IA del espacio (Conexiones → IA).");
        if (status == 402) throw new AgentModelException("La cuenta del proveedor de IA no tiene saldo: recárgala y reintenta.");
        if (status == 429) throw new AgentModelException("Se alcanzó el límite de uso o la cuota de la cuenta del proveedor de IA: " + errorOf(res.body()));
        if (status >= 300) throw new AgentModelException("El proveedor de IA respondió " + status + ": " + errorOf(res.body()));
        try {
            return json.readTree(res.body());
        } catch (RuntimeException e) {
            throw new AgentModelException("El proveedor de IA devolvió una respuesta que no se entiende.", e);
        }
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AgentModelException("No se pudo hablar con el proveedor de IA: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentModelException("Se interrumpió la llamada al proveedor de IA.", e);
        }
    }

    private JsonNode arguments(String raw) {
        try {
            JsonNode node = json.readTree(raw.isBlank() ? "{}" : raw);
            return node.isObject() ? node : null;
        } catch (RuntimeException e) {
            log.debug("Argumentos inválidos de una herramienta: {}", e.getMessage());
            return null;
        }
    }

    private String errorOf(String body) {
        try {
            JsonNode n = json.readTree(body);
            String msg = n.path("error").path("message").asString(n.path("message").asString(""));
            if (!msg.isBlank()) return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
        } catch (RuntimeException ignored) {
            // no era JSON
        }
        return body == null ? "" : body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }

    private static URI uri(Endpoint endpoint, String path) {
        String base = endpoint.baseUrl().strip();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return URI.create(base + path);
    }

    /** Corre una herramienta del tablero; un error vuelve al modelo para que corrija. */
    private String runBoardTool(BoardAgentModel.BoardTool tool, JsonNode args) {
        try {
            Map<String, Object> map = json.convertValue(args, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
            return tool.run().apply(map);
        } catch (RuntimeException e) {
            return "Error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private ObjectNode tool(String name, String description, Map<String, Object> parameters) {
        ObjectNode t = json.createObjectNode().put("type", "function");
        ObjectNode f = t.putObject("function").put("name", name).put("description", description);
        f.set("parameters", json.valueToTree(parameters));
        return t;
    }

    private static Map<String, Object> object(String field, String description) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("type", "object");
        o.put("properties", Map.of(field, Map.of("type", "string", "description", description)));
        o.put("required", new ArrayList<>(List.of(field)));
        return o;
    }
}
