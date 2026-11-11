package ec.kuador.board.notify.infrastructure.channels;

import ec.kuador.board.notify.application.NotificationService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Despacha la cola cada pocos segundos y recibe los mensajes del bot. Con la API en HTTPS (producción), Telegram los
 * entrega por webhook; en local (sin HTTPS), se piden con getUpdates.
 */
@Component
class NoticeDispatcher implements ec.kuador.board.notify.infrastructure.web.TelegramWebhook {

    private static final Logger log = LoggerFactory.getLogger(NoticeDispatcher.class);

    private final NotificationService notifications;
    private final TelegramBot bot;
    private final String publicUrl;
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final AtomicLong offset = new AtomicLong(0);

    NoticeDispatcher(NotificationService notifications, TelegramBot bot, @Value("${board.public-url:http://localhost:8091}") String publicUrl) {
        this.notifications = notifications;
        this.bot = bot;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    @Scheduled(fixedDelayString = "${board.notifications.dispatch-every:5000}", initialDelay = 5000)
    void dispatch() {
        try {
            int sent = notifications.dispatch(20);
            if (sent > 0) log.debug("{} aviso(s) enviados", sent);
        } catch (RuntimeException e) {
            log.warn("Falló el despacho de avisos: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void connectBot() {
        if (!bot.configured()) return;
        if (publicUrl.startsWith("https://")) {
            try {
                bot.call("setWebhook", java.util.Map.of("url", publicUrl + "/api/telegram/webhook", "secret_token", bot.webhookSecret(),
                        "allowed_updates", "[\"message\"]"));
                log.info("Bot de Telegram @{} recibe mensajes por webhook", bot.username());
            } catch (RuntimeException e) {
                log.warn("No se pudo registrar el webhook de Telegram: {}", e.getMessage());
            }
        } else {
            polling.set(true);
            log.info("Bot de Telegram @{} en modo local: mensajes con getUpdates", bot.username());
        }
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 5000)
    void poll() {
        if (!polling.get()) return;
        try {
            JsonNode updates = bot.call("getUpdates", java.util.Map.of("offset", String.valueOf(offset.get()), "timeout", "0", "allowed_updates", "[\"message\"]"));
            for (JsonNode u : updates) {
                offset.set(u.path("update_id").asLong() + 1);
                handle(u);
            }
        } catch (TelegramBot.TelegramException e) {
            if (e.status == 409) {
                // El bot ya entrega por webhook (lo usa otro servidor): en local no se puede vincular con este bot.
                polling.set(false);
                log.warn("El bot de Telegram tiene un webhook activo en otro servidor; en local usa otro bot para vincular.");
            } else {
                log.debug("getUpdates falló: {}", e.getMessage());
            }
        }
    }

    @Override
    public void handle(JsonNode update) {
        JsonNode message = update.path("message");
        if (message.isMissingNode() || !message.path("chat").has("id")) return;
        if (!"private".equals(message.path("chat").path("type").asString("private"))) return; // solo chats directos con el bot
        notifications.onTelegramMessage(message.path("chat").path("id").asLong(), message.path("text").asString(""));
    }
}
