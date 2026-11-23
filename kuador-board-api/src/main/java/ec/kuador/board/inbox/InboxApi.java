package ec.kuador.board.inbox;

import java.util.UUID;

/** Lo que otros módulos usan de la bandeja de correo de los tableros. */
public interface InboxApi {

    /**
     * Que los correos nuevos de un buzón entren al tablero (en su primera columna, desde ahora). No hace nada si el
     * tablero ya recibe ese buzón.
     */
    void receive(String projectKey, String connection, UUID createdBy);
}
