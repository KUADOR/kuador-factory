package ec.kuador.board.notify.infrastructure.channels;

import ec.kuador.board.notify.domain.Channel;
import org.springframework.stereotype.Component;

/** Avisos por Telegram al chat que la persona vinculó con el bot del tablero. */
@Component
class TelegramChannel implements Channel {

    private final TelegramBot bot;

    TelegramChannel(TelegramBot bot) {
        this.bot = bot;
    }

    @Override
    public Kind kind() {
        return Kind.TELEGRAM;
    }

    @Override
    public boolean available() {
        return bot.configured();
    }

    @Override
    public void send(String address, String subject, String body, String link) {
        bot.sendMessage(Long.parseLong(address), subject + "\n\n" + body + (link == null ? "" : "\n\n" + link));
    }
}
