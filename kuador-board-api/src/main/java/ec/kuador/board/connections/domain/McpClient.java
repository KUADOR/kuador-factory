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

    /** El servidor no aceptó la credencial: falta, venció o se revocó. */
    class UnauthorizedException extends McpException {
        public UnauthorizedException(String message) {
            super(message);
        }
    }

    List<Connection.ToolDef> listTools(String url, String header, String secret);

    /** Si la dirección es la de un buzón de correo que el tablero sabe leer (Outlook, Gmail o IMAP). */
    default boolean isMailbox(String url) {
        return false;
    }

    /** Los correos nuevos de un buzón (ver {@link ec.kuador.board.connections.ConnectionsApi#recentMail}). */
    default List<ec.kuador.board.connections.ConnectionsApi.IncomingMail> recentMail(String url, String secret, String folder, java.time.Instant since, int max) {
        return recentMail(url, secret, folder, since, max, java.util.Set.of());
    }

    default List<ec.kuador.board.connections.ConnectionsApi.IncomingMail> recentMail(String url, String secret, String folder, java.time.Instant since, int max,
            java.util.Set<String> known) {
        throw new McpException("Esta conexión no es un buzón de correo.");
    }

    /** El texto de un documento público (la documentación de una API), con tope de tamaño y solo hacia Internet. */
    default String fetchDocument(String url) {
        throw new McpException("No se pueden descargar documentos desde aquí.");
    }

    /** El texto que devuelve la herramienta. Si la herramienta informa un error, lanza {@link McpException} con él. */
    String callTool(String url, String header, String secret, String tool, Map<String, Object> arguments);
}
