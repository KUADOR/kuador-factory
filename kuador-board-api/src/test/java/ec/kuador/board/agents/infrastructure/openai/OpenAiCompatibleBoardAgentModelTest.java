package ec.kuador.board.agents.infrastructure.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import ec.kuador.board.agents.domain.AgentModelException;
import ec.kuador.board.agents.domain.BoardAgentModel;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Un agente con un proveedor compatible con OpenAI (como DeepSeek) contra una API falsa: consulta la planilla, crea
 * el PDF y entrega; el razonamiento vuelve en la siguiente llamada y la entrega espera a que se cree el archivo.
 */
class OpenAiCompatibleBoardAgentModelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<String> replies = new ArrayList<>();
    private HttpServer server;
    private OpenAiCompatibleBoardAgentModel model;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> {
            boolean ok = "Bearer sk-clave-buena-de-prueba-123".equals(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = (ok ? "{\"data\":[]}" : "{\"error\":{\"message\":\"Invalid key\"}}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ok ? 200 : 401, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/v1/chat/completions", ex -> {
            requests.add(JSON.readTree(ex.getRequestBody().readAllBytes()));
            byte[] body = replies.removeFirst().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
        model = new OpenAiCompatibleBoardAgentModel(JSON);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String toolCalls(String reasoning, String... calls) {
        return """
                {"model": "deepseek-prueba", "usage": {"prompt_tokens": 1000, "completion_tokens": 200},
                 "choices": [{"finish_reason": "tool_calls", "message": {"role": "assistant", "content": null, "reasoning_content": %s,
                   "tool_calls": [%s]}}]}""".formatted(reasoning == null ? "null" : JSON.writeValueAsString(reasoning), String.join(",", calls));
    }

    private static String call(String id, String name, String args) {
        return """
                {"id": "%s", "type": "function", "function": {"name": "%s", "arguments": %s}}""".formatted(id, name, JSON.writeValueAsString(args));
    }

    @Test
    void checksTheKeyAgainstTheProvider() {
        model.checkKey(new BoardAgentModel.Endpoint(base, "sk-clave-buena-de-prueba-123"));
        assertThatThrownBy(() -> model.checkKey(new BoardAgentModel.Endpoint(base, "sk-otra-clave-de-prueba-1234")))
                .isInstanceOf(AgentModelException.class).hasMessageContaining("rechazó la clave");
    }

    @Test
    void worksWithTheBoardToolsAndDeliversTheDocument() throws Exception {
        replies.add(toolCalls("Primero sumo las ventas.", call("c1", "query_data", "{\"sql\":\"select sum(total) as vendido from ventas\"}")));
        replies.add(toolCalls(null,
                call("c2", "deliver", "{\"summary\":\"Vendiste $163.85 en octubre. Adjunté el resumen.\"}"),
                call("c3", "create_document", "{\"file_name\":\"resumen.pdf\",\"title\":\"Ventas\",\"blocks\":[{\"type\":\"kpis\",\"items\":[{\"label\":\"Vendido\",\"value\":\"$163.85\"}]}]}")));

        BoardAgentModel.Result r = model.work(new BoardAgentModel.Endpoint(base, "sk-clave-buena-de-prueba-123"), "deepseek-prueba",
                new BoardAgentModel.Task("Eres el Analista.", "Tarjeta FLO-1: informe de octubre",
                        List.of(new BoardAgentModel.InputFile("ventas.xlsx", null, ec.kuador.board.agents.infrastructure.tools.WorkRoomTest.salesExcel()))));

        assertThat(r.outcome()).isEqualTo(BoardAgentModel.Outcome.DELIVERED);
        assertThat(r.text()).contains("$163.85");
        assertThat(r.files()).extracting(BoardAgentModel.OutputFile::name).containsExactly("resumen.pdf");
        assertThat(r.inputTokens()).isEqualTo(2000);
        assertThat(r.model()).isEqualTo("deepseek-prueba");

        JsonNode first = requests.getFirst();
        assertThat(first.path("messages").get(0).path("content").asString()).contains("Eres el Analista.", "query_data", "create_document");
        assertThat(first.path("messages").get(1).path("content").asString()).contains("FLO-1", "Tabla ventas", "total NUMBER");
        assertThat(first.path("tools").valueStream().map(t -> t.path("function").path("name").asString()).toList()).contains("read_file", "query_data", "create_document", "deliver", "ask_human");
        JsonNode second = requests.get(1).path("messages");
        assertThat(second.get(2).path("reasoning_content").asString()).isEqualTo("Primero sumo las ventas.");
        assertThat(second.get(3).path("role").asString()).isEqualTo("tool");
        assertThat(second.get(3).path("content").asString()).contains("| 163.85 |");
    }

    @Test
    void asksWhenSomethingIsMissingAndExplainsProviderErrors() {
        replies.add(toolCalls(null, call("c1", "ask_human", "{\"question\":\"¿De qué mes quieres el informe?\"}")));
        BoardAgentModel.Result r = model.work(new BoardAgentModel.Endpoint(base, "sk-x-123456789012345678901"), "m",
                new BoardAgentModel.Task("s", "u", List.of()));
        assertThat(r.outcome()).isEqualTo(BoardAgentModel.Outcome.QUESTION);
        assertThat(r.text()).isEqualTo("¿De qué mes quieres el informe?");

        server.removeContext("/v1/chat/completions");
        server.createContext("/v1/chat/completions", ex -> {
            byte[] body = "{\"error\":{\"message\":\"Insufficient Balance\"}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(402, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        assertThatThrownBy(() -> model.work(new BoardAgentModel.Endpoint(base, "sk-x-123456789012345678901"), "m", new BoardAgentModel.Task("s", "u", List.of())))
                .isInstanceOf(AgentModelException.class).hasMessageContaining("no tiene saldo");
    }
}
