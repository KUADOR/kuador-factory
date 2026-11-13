package ec.kuador.board.agents.infrastructure.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import ec.kuador.board.agents.domain.AgentModelException;
import ec.kuador.board.agents.domain.BoardAgentModel;
import ec.kuador.board.testing.AnthropicSse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** El agente del tablero pide cada turno en streaming y con tope alto; una API falsa responde en SSE. */
class ClaudeBoardAgentModelTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private final AtomicReference<JsonNode> lastBody = new AtomicReference<>();
    private final java.util.Deque<String> replies = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private volatile String reply;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            lastBody.set(JSON.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String next = replies.isEmpty() ? reply : replies.poll();
            byte[] out = AnthropicSse.of(next).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private BoardAgentModel.Result work() {
        return work(new BoardAgentModel.Task("Reglas", "Haz el informe", List.of()));
    }

    private BoardAgentModel.Result work(BoardAgentModel.Task task) {
        return new ClaudeBoardAgentModel("http://127.0.0.1:" + server.getAddress().getPort())
                .work(new BoardAgentModel.Endpoint(null, "sk-ant-prueba"), "claude-opus-5-5", task);
    }

    @Test
    void anEpicCannotBeDeliveredUntilTheBoardToolCreatesItsTasks() {
        List<Object> created = new java.util.ArrayList<>();
        var createTasks = new BoardAgentModel.BoardTool("create_tasks", "Crea las tareas",
                java.util.Map.of("type", "object", "required", List.of("tasks"), "properties", java.util.Map.of("tasks", java.util.Map.of("type", "array"))),
                args -> {
                    created.addAll((List<?>) args.get("tasks"));
                    return "Creé 2 tarea(s).";
                });
        var task = new BoardAgentModel.Task("Reglas", "Planifica la épica", List.of(), List.of(createTasks),
                () -> created.isEmpty() ? "Es una épica sin tareas: crea las tareas con create_tasks." : null);
        // 1) Entrega sin tareas: se le rechaza. 2) Crea las tareas. 3) Entrega.
        replies.add(message("tool_use", """
                {"type":"tool_use","id":"toolu_1","name":"deliver","input":{"summary":"Plan listo"}}"""));
        replies.add(message("tool_use", """
                {"type":"tool_use","id":"toolu_2","name":"create_tasks","input":{"tasks":[{"title":"API","role":"BACKEND"},{"title":"Web","role":"FRONTEND","dependsOn":[1]}]}}"""));
        replies.add(message("tool_use", """
                {"type":"tool_use","id":"toolu_3","name":"deliver","input":{"summary":"Plan listo con 2 tareas"}}"""));

        BoardAgentModel.Result r = work(task);

        assertThat(r.outcome()).isEqualTo(BoardAgentModel.Outcome.DELIVERED);
        assertThat(r.text()).isEqualTo("Plan listo con 2 tareas");
        assertThat(created).hasSize(2);
        JsonNode last = lastBody.get();
        assertThat(last.path("tools").findValues("name").stream().map(JsonNode::asString)).contains("create_tasks");
        String sent = last.path("messages").toString();
        assertThat(sent).contains("crea las tareas con create_tasks").contains("Creé 2 tarea(s).");
    }

    private static String message(String stopReason, String content) {
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":1200,"output_tokens":800},"content":[%s]}""".formatted(stopReason, content);
    }

    @Test
    void streamsEachTurnAndReadsTheDelivery() {
        reply = message("tool_use", """
                {"type":"text","text":"Listo."},
                {"type":"tool_use","id":"toolu_1","name":"deliver","input":{"summary":"Ventas de octubre: USD 1.250."}}""");

        BoardAgentModel.Result r = work();

        assertThat(r.outcome()).isEqualTo(BoardAgentModel.Outcome.DELIVERED);
        assertThat(r.text()).isEqualTo("Ventas de octubre: USD 1.250.");
        assertThat(r.inputTokens()).isEqualTo(1200);
        assertThat(lastBody.get().path("stream").asBoolean()).isTrue();
        assertThat(lastBody.get().path("max_tokens").asInt()).isEqualTo(64000);
    }

    @Test
    void aCutAnswerSaysWhy() {
        reply = message("max_tokens", """
                {"type":"text","text":"Empiezo a calcular"}""");

        assertThatThrownBy(this::work).isInstanceOf(AgentModelException.class).hasMessageContaining("64000").hasMessageContaining("se cortó");
    }
}
