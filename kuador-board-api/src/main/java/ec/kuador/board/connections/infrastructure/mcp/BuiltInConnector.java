package ec.kuador.board.connections.infrastructure.mcp;

import ec.kuador.board.connections.domain.Connection;
import java.util.List;
import java.util.Map;

/** Un conector que trae el propio tablero (Outlook, Gmail, correo IMAP), para sistemas que no publican servidor MCP. */
interface BuiltInConnector {

    /** Si la dirección de la conexión es la de este conector (y no la de un servidor MCP). */
    boolean handles(String url);

    /** Las herramientas que ofrece; comprueba antes que la credencial sirva. {@code secret}: la credencial ya descifrada. */
    List<Connection.ToolDef> tools(String secret);

    String call(String secret, String tool, Map<String, Object> arguments);

    /** Si es un buzón de correo (sabe entregar los correos nuevos). */
    default boolean mailbox() {
        return false;
    }

    /** Los correos que llegaron a una carpeta desde {@code since}, del más antiguo al más nuevo, con sus adjuntos. */
    default List<ec.kuador.board.connections.ConnectionsApi.IncomingMail> recent(String secret, String folder, java.time.Instant since, int max,
            java.util.Set<String> known) {
        throw new ec.kuador.board.connections.domain.McpClient.McpException("Esta conexión no es un buzón de correo.");
    }
}
