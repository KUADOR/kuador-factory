package ec.kuador.board.notify.domain;

/** Puerto: un canal de avisos (correo, Telegram). */
public interface Channel {

    enum Kind {
        EMAIL,
        TELEGRAM
    }

    Kind kind();

    /** Configurado en este servidor (SMTP, bot de Telegram). */
    boolean available();

    /** Envía; lanza si falla (la cola reintenta). */
    void send(String address, String subject, String body, String link);
}
