package ec.kuador.board.mcp.application;

import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.ForbiddenException;
import ec.kuador.board.shared.error.NotFoundException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ec.kuador.board.workspace.WorkspaceApi;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Model Context Protocol sobre HTTP (transporte «streamable HTTP», sin flujo SSE): cada POST trae un
 * mensaje JSON-RPC y recibe su respuesta como JSON. Sin estado entre peticiones: la identidad viaja
 * en el token de cada una.
 */
@Service
public class McpProtocol {

    /** Versiones del protocolo que entiende el servidor, de la más nueva a la más vieja. */
    static final List<String> VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");

    private static final Logger log = LoggerFactory.getLogger(McpProtocol.class);
    private static final String INSTRUCTIONS = """
            Tablero de %s. Trabajas como los agentes del equipo sobre los issues que supervisa la persona \
            conectada. Empieza por «pending_work»; para cada issue, «get_issue» y «role_manual» del agente que lo cubre; \
            luego «take_issue», trabaja y termina con «deliver», «send_back» o «ask_human». Las puertas de aprobación las \
            cierra siempre una persona. El prompt «pendientes» trae estos pasos.""";

    /** Prompt del servidor: en Claude Code aparece como el comando /mcp__<servidor>__pendientes. */
    static final String WORK_PROMPT = "pendientes";

    private final BoardTools tools;
    private final AgentKit kit;
    private final ObjectMapper json;
    private final WorkspaceApi workspace;

    McpProtocol(BoardTools tools, AgentKit kit, ObjectMapper json, WorkspaceApi workspace) {
        this.tools = tools;
        this.kit = kit;
        this.json = json;
        this.workspace = workspace;
    }

    /** Respuesta JSON-RPC al mensaje, o vacío si era una notificación (no lleva respuesta). */
    public Optional<Map<String, Object>> handle(JsonNode message, UUID personId, String client) {
        if (message == null || !message.isObject()) return Optional.of(error(null, -32600, "Se esperaba un mensaje JSON-RPC (objeto)."));
        JsonNode id = message.get("id");
        String method = message.path("method").asString("");
        if (id == null || id.isNull()) return Optional.empty(); // notificación (p. ej. notifications/initialized) o respuesta
        JsonNode params = message.path("params");
        return Optional.of(switch (method) {
            case "initialize" -> result(id, initialize(params));
            case "ping" -> result(id, Map.of());
            case "tools/list" -> result(id, Map.of("tools", tools.list().stream().map(McpProtocol::describe).toList()));
            case "tools/call" -> result(id, call(params, personId, client));
            case "prompts/list" -> result(id, Map.of("prompts", List.of(workPrompt())));
            case "prompts/get" -> prompt(id, params);
            default -> error(id, -32601, "Método no soportado: " + method);
        });
    }

    /** El servidor se presenta con el nombre de la empresa del espacio, no con el del producto. */
    private Map<String, Object> initialize(JsonNode params) {
        String asked = params.path("protocolVersion").asString("");
        WorkspaceApi.Identity ws = workspace.identity();
        return Map.of(
                "protocolVersion", VERSIONS.contains(asked) ? asked : VERSIONS.getFirst(),
                "capabilities", Map.of("tools", Map.of("listChanged", false), "prompts", Map.of("listChanged", false)),
                "serverInfo", Map.of("name", ws.slug(), "title", "Tablero de " + ws.name(), "version", "1.0.0"),
                "instructions", INSTRUCTIONS.formatted(ws.name()));
    }

    private Map<String, Object> workPrompt() {
        return Map.of("name", WORK_PROMPT, "title", "Trabajar mis pendientes",
                "description", "Atiende los issues del tablero de " + workspace.identity().name() + " que esperan a tus agentes, uno por uno.",
                "arguments", List.of(Map.of("name", "filtro", "description", "Opcional: clave de proyecto (p. ej. MED) o rol (p. ej. QA); agrega --paralelo para trabajar varias tareas a la vez.", "required", false)));
    }

    private Map<String, Object> prompt(JsonNode id, JsonNode params) {
        String name = params.path("name").asString("");
        if (!WORK_PROMPT.equals(name)) return error(id, -32602, "No existe el prompt " + name + ".");
        String filter = params.path("arguments").path("filtro").asString("");
        return result(id, Map.of("description", "Trabajar mis pendientes",
                "messages", List.of(Map.of("role", "user", "content", text(kit.workPrompt(filter, false))))));
    }

    private static Map<String, Object> describe(Tool t) {
        return Map.of("name", t.name(), "title", t.title(), "description", t.description(), "inputSchema", t.inputSchema(),
                "annotations", Map.of("title", t.title(), "readOnlyHint", t.readOnly(), "destructiveHint", false, "openWorldHint", false));
    }

    /** Un error del tablero vuelve como resultado con {@code isError}: el agente lo lee y corrige. */
    private Map<String, Object> call(JsonNode params, UUID personId, String client) {
        String name = params.path("name").asString("");
        Map<String, String> args = new LinkedHashMap<>();
        params.path("arguments").properties().forEach(e -> {
            if (!e.getValue().isNull()) args.put(e.getKey(), e.getValue().isString() ? e.getValue().asString() : e.getValue().toString());
        });
        try {
            Object result = tools.call(name, args, personId, client);
            return Map.of("content", List.of(text(json.writerWithDefaultPrettyPrinter().writeValueAsString(result))), "isError", false);
        } catch (ToolException | DomainException | ForbiddenException | NotFoundException | ConflictException e) {
            return Map.of("content", List.of(text(e.getMessage())), "isError", true);
        } catch (RuntimeException e) {
            log.error("Falló la herramienta MCP {}", name, e);
            return Map.of("content", List.of(text("Error interno del tablero. Inténtalo de nuevo o avisa a una persona.")), "isError", true);
        }
    }

    private static Map<String, Object> text(String value) {
        return Map.of("type", "text", "text", value);
    }

    private static Map<String, Object> result(JsonNode id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    static Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message));
        return m;
    }
}
