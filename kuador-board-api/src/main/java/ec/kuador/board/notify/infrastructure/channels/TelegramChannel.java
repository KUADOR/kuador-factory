package ec.kuador.board.notify.infrastructure.channels;

import ec.kuador.board.notify.application.TelegramBots;
import ec.kuador.board.notify.domain.Channel;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Avisos por Telegram con el bot del espacio (el propio o el de la plataforma), a una persona o a un grupo. */
@Component
class TelegramChannel implements Channel {

    private final TelegramBot telegram;
    private final TelegramBots bots;

    TelegramChannel(TelegramBot telegram, TelegramBots bots) {
        this.telegram = telegram;
        this.bots = bots;
    }

    @Override
    public Kind kind() {
        return Kind.TELEGRAM;
    }

    @Override
    public void send(UUID workspaceId, String address, String subject, String body, String link) {
        var bot = bots.forWorkspace(workspaceId).orElseThrow(() -> new IllegalStateException("El espacio ya no tiene bot de Telegram."));
        telegram.sendMessage(bot.token(), Long.parseLong(address), subject + "\n\n" + body + (link == null ? "" : "\n\n" + link));
    }
}
