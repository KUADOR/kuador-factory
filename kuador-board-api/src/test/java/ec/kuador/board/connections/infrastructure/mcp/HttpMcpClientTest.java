package ec.kuador.board.connections.infrastructure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import ec.kuador.board.connections.domain.Connection;
import ec.kuador.board.connections.domain.McpClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Contra un servidor MCP mínimo en la propia máquina: una vez responde JSON y otra un flujo de eventos. */
class HttpMcpClientTest {

    private HttpServer server;
    private String url;
    private final List<String> seen = new ArrayList<>();
    private boolean events;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestHeaders().getFirst("Authorization") + " | " + exchange.getRequestHeaders().getFirst("Mcp-Session-Id") + " | " + body);
            if (!"Bearer secreto".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            String reply;
            if (body.contains("\"initialize\"")) {
                exchange.getResponseHeaders().add("Mcp-Session-Id", "s-1");
                reply = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"agenda\",\"version\":\"1\"}}}";
            } else if (body.contains("notifications/initialized")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            } else if (body.contains("tools/list")) {
                reply = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":["
                        + "{\"name\":\"buscar_citas\",\"description\":\"Citas de un día\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"dia\":{\"type\":\"string\"}}},\"annotations\":{\"readOnlyHint\":true}},"
                        + "{\"name\":\"crear_cita\",\"description\":\"Agenda una cita\",\"inputSchema\":{\"type\":\"object\"}}]}}";
            } else if (body.contains("\"fallar\"")) {
                reply = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"No existe ese paciente\"}]}}";
            } else {
                reply = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"3 citas el lunes\"}]}}";
            }
            byte[] out;
            if (events && !body.contains("\"initialize\"")) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                out = ("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\nevent: message\ndata: " + reply + "\n\n")
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                out = reply.getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void listsToolsAndKnowsWhichOnlyRead() {
        List<Connection.ToolDef> tools = new HttpMcpClient(true).listTools(url, null, "secreto");

        assertThat(tools).extracting(Connection.ToolDef::name).containsExactly("buscar_citas", "crear_cita");
        assertThat(tools.get(0).readOnly()).isTrue();
        assertThat(tools.get(1).readOnly()).as("sin declaración, se trata como que escribe").isFalse();
        assertThat(seen.getLast()).as("la sesión que abrió el servidor viaja en los pedidos siguientes").contains("| s-1 |");
    }

    @Test
    void callsAToolOverJsonAndOverEvents() {
        assertThat(new HttpMcpClient(true).callTool(url, null, "secreto", "buscar_citas", Map.of("dia", "lunes"))).isEqualTo("3 citas el lunes");
        events = true;
        assertThat(new HttpMcpClient(true).callTool(url, null, "secreto", "buscar_citas", Map.of("dia", "lunes"))).isEqualTo("3 citas el lunes");
    }

    @Test
    void errorsComeBackAsReadableMessages() {
        assertThatThrownBy(() -> new HttpMcpClient(true).callTool(url, null, "secreto", "fallar", Map.of()))
                .isInstanceOf(McpClient.McpException.class).hasMessageContaining("No existe ese paciente");
        assertThatThrownBy(() -> new HttpMcpClient(true).listTools(url, null, "otra")).hasMessageContaining("rechazó la credencial");
        assertThatThrownBy(() -> new HttpMcpClient(false).listTools(url, null, "secreto")).as("sin permiso, nunca hacia la red interna")
                .isInstanceOf(McpClient.McpException.class).hasMessageContaining("https");
    }
}
