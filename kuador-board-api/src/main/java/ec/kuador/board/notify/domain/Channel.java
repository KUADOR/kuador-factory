package ec.kuador.board.notify.domain;

import java.util.UUID;

/** Puerto: un canal de avisos (correo, Telegram, notificaciones push de la app instalada). */
public interface Channel {

    enum Kind {
        EMAIL,
        TELEGRAM,
        /** Notificación del navegador o de la app instalada; {@code address} es el id de la suscripción del dispositivo. */
        PUSH
    }

    Kind kind();

    /** Configurado en este servidor (p. ej. hay SMTP). Telegram depende además del bot del espacio. */
    default boolean available() {
        return true;
    }

    /** Envía; lanza si falla (la cola reintenta). {@code workspaceId}: el espacio del aviso (Telegram usa su bot). */
    void send(UUID workspaceId, String address, String subject, String body, String link);
}
