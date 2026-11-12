package ec.kuador.board.notify.domain;

/** Puerto: la Bot API de Telegram, con el token del bot que corresponda. */
public interface TelegramGateway {

    /** Valida el token y devuelve el nombre del bot (sin «@»). */
    String username(String token);

    void sendMessage(String token, long chatId, String text);

    /** Telegram entregará los mensajes del bot en {@code url} con ese secreto. */
    void setWebhook(String token, String url, String secret);

    void deleteWebhook(String token);
}
