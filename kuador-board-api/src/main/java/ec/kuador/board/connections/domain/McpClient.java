package ec.kuador.board.connections.domain;

import java.util.List;
import java.util.Map;

/** Puerto: hablar MCP con el servidor de una conexión. {@code secret}: la credencial ya descifrada, o nula. */
public interface McpClient {

    /** No se pudo hablar con el servidor o respondió un error. El mensaje se le puede mostrar a una persona. */
    class McpException extends RuntimeException {
        public McpException(String message) {
            super(message);
        }
    }

    List<Connection.ToolDef> listTools(String url, String header, String secret);

    /** El texto que devuelve la herramienta. Si la herramienta informa un error, lanza {@link McpException} con él. */
    String callTool(String url, String header, String secret, String tool, Map<String, Object> arguments);
}
