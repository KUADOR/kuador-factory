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
}
