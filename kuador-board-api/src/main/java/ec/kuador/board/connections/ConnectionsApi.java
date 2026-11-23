package ec.kuador.board.connections;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lo que otros módulos (los agentes) usan de las conexiones del negocio. */
public interface ConnectionsApi {

    /**
     * Una herramienta de una conexión que un rol puede usar en un tablero. {@code name}: el nombre con el que la ve el
     * modelo (conexión y herramienta, único en el tablero). {@code schema}: JSON Schema de sus argumentos.
     */
    record Tool(String name, String connection, String connectionName, String tool, String description, Map<String, Object> schema, boolean readOnly) {}

    /**
     * Un correo que llegó a un buzón conectado. {@code id}: el del proveedor (para leerlo o responderlo con las
     * herramientas); {@code messageId}: el Message-ID del correo, que no cambia aunque se mueva de carpeta (puede faltar).
     */
    record IncomingMail(String id, String messageId, String from, String subject, java.time.Instant receivedAt, String body, List<MailFile> files) {}

    /** Un adjunto de un correo. {@code content} nulo si pesaba demasiado para traerlo. */
    record MailFile(String name, String contentType, long size, byte[] content) {}

    /** Un buzón conectado (Outlook, Gmail o correo con contraseña): los que pueden alimentar la bandeja de un tablero. */
    record Mailbox(String key, String name) {}

    List<Mailbox> mailboxes();

    /** Un sistema conectado, para proponer tableros que lo usen: qué consulta y qué podría cambiar. */
    record Summary(String key, String name, boolean mailbox, List<Tool> reads, List<Tool> writes) {}

    /** Los sistemas conectados del espacio (sin los que esperan iniciar sesión). */
    List<Summary> summaries();

    /** Habilita herramientas que escriben (se suman a las ya habilitadas). */
    void enableWrites(String connection, List<String> tools);

    /**
     * Los correos que llegaron a una carpeta desde {@code since}, del más antiguo al más nuevo (hasta {@code max}), con
     * su texto y sus adjuntos. {@code folder} nulo = la bandeja de entrada.
     */
    List<IncomingMail> recentMail(String connection, String folder, java.time.Instant since, int max);

    /** Que el tablero pueda usar la conexión (todos sus roles), si todavía no la usa. */
    void useOnBoard(String connection, String projectKey);

    /** Las herramientas que el rol puede usar en el tablero: las que solo leen y las de escritura habilitadas. */
    List<Tool> toolsFor(String projectKey, String roleKey);

    /**
     * Llama a una herramienta en nombre de un agente y devuelve el texto de su respuesta. Vuelve a comprobar que el rol
     * la puede usar en ese tablero. {@code issueKey}: la tarjeta que se trabaja, para el registro.
     */
    String call(String projectKey, String roleKey, String toolName, Map<String, Object> arguments, UUID actorId, String issueKey);
}
