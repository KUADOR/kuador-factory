package ec.kuador.board.notify.domain;

/** Puerto: el bot de Telegram del tablero. */
public interface TelegramGateway {

    /** Hay token y nombre de bot configurados. */
    boolean configured();

    /** Nombre del bot, sin «@» (para el enlace t.me). */
    String username();

    void sendMessage(long chatId, String text);
}
