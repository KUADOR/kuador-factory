package ec.kuador.board.notify.infrastructure.channels;

import ec.kuador.board.notify.application.NotificationService;
import ec.kuador.board.notify.application.TelegramBots;
import ec.kuador.board.notify.domain.TelegramBotConfig;
import ec.kuador.board.shared.tenant.TenantContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Despacha la cola cada pocos segundos y recibe los mensajes de los bots. Con la API en HTTPS (producción), Telegram
 * los entrega por webhook (uno por bot); en local (sin HTTPS), se piden con getUpdates a cada bot.
 */
@Component
class NoticeDispatcher implements ec.kuador.board.notify.infrastructure.web.TelegramWebhook {

    private static final Logger log = LoggerFactory.getLogger(NoticeDispatcher.class);

    private final NotificationService notifications;
    private final TelegramBots bots;
    private final TelegramBot telegram;
    private final String publicUrl;
    private final Map<String, Long> offsets = new ConcurrentHashMap<>();
    /** Bots que entregan por webhook en otro servidor: en local no se les pide getUpdates. */
    private final Set<String> skip = ConcurrentHashMap.newKeySet();

    NoticeDispatcher(NotificationService notifications, TelegramBots bots, TelegramBot telegram,
            @Value("${board.public-url:http://localhost:8091}") String publicUrl) {
        this.notifications = notifications;
        this.bots = bots;
        this.telegram = telegram;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    boolean webhooks() {
        return publicUrl.startsWith("https://");
    }

    /** URL del webhook de un bot. */
    String webhookUrl(TelegramBotConfig bot) {
        return publicUrl + "/api/telegram/webhook/" + bot.key();
    }

    @Scheduled(fixedDelayString = "${board.notifications.dispatch-every:5000}", initialDelay = 5000)
    void dispatch() {
        try {
            int sent = notifications.dispatch(20);
            if (sent > 0) log.debug("{} aviso(s) enviados", sent);
        } catch (RuntimeException e) {
            log.error("Falló el despacho de avisos: {}", e.getMessage());
        }
    }

    /** Al arrancar, en producción, cada bot (re)registra su webhook. */
    @EventListener(ApplicationReadyEvent.class)
    void connectBots() {
        if (!webhooks()) return;
        for (TelegramBotConfig bot : TenantContext.asRoot(bots::all)) {
            try {
                telegram.setWebhook(bot.token(), webhookUrl(bot), bot.webhookSecret());
                log.info("Bot de Telegram @{} recibe mensajes por webhook", bot.username());
            } catch (RuntimeException e) {
                log.warn("No se pudo registrar el webhook de @{}: {}", bot.username(), e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 5000)
    void poll() {
        if (webhooks()) return;
        java.util.List<TelegramBotConfig> all;
        try {
            all = TenantContext.asRoot(bots::all);
        } catch (RuntimeException e) {
            log.debug("No se pudieron leer los bots de Telegram: {}", e.getMessage());
            return;
        }
        for (TelegramBotConfig bot : all) {
            if (skip.contains(bot.key())) continue;
            try {
                for (JsonNode u : telegram.updates(bot.token(), offsets.getOrDefault(bot.key(), 0L))) {
                    offsets.put(bot.key(), u.path("update_id").asLong() + 1);
                    handle(u, bot.key());
                }
            } catch (TelegramBot.TelegramException e) {
                if (e.status == 409) {
                    skip.add(bot.key());
                    log.warn("El bot @{} tiene un webhook activo en otro servidor; en local usa otro bot para vincular.", bot.username());
                } else {
                    log.debug("getUpdates de @{} falló: {}", bot.username(), e.getMessage());
                }
            } catch (RuntimeException e) {
                log.debug("No se pudieron leer los mensajes de @{}: {}", bot.username(), e.getMessage());
            }
        }
    }

    @Override
    public void handle(JsonNode update, String botKey) {
        JsonNode message = update.path("message");
        if (message.isMissingNode() || !message.path("chat").has("id")) return;
        JsonNode chat = message.path("chat");
        notifications.onTelegramMessage(botKey, chat.path("id").asLong(), chat.path("type").asString("private"), chat.path("title").asString(""),
                message.path("text").asString(""));
    }
}
