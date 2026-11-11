package ec.kuador.board.notify.infrastructure.web;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.notify.application.NotificationService;
import ec.kuador.board.notify.domain.Channel;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Avisos de cada persona (sus propias preferencias) y el webhook del bot de Telegram. */
@RestController
class NotificationController {

    private final NotificationService service;
    private final ec.kuador.board.notify.infrastructure.channels.TelegramBot bot;
    private final TelegramWebhook webhook;

    NotificationController(NotificationService service, ec.kuador.board.notify.infrastructure.channels.TelegramBot bot, TelegramWebhook webhook) {
        this.service = service;
        this.bot = bot;
        this.webhook = webhook;
    }

    record SettingsRequest(boolean email, boolean telegram, boolean onAssigned, boolean onGates, boolean onPlans) {}

    @GetMapping("/api/notifications")
    NotificationService.View get(@AuthenticationPrincipal AuthenticatedMember who) {
        return service.view(who);
    }

    @PutMapping("/api/notifications")
    NotificationService.View update(@AuthenticationPrincipal AuthenticatedMember who, @RequestBody SettingsRequest r) {
        return service.update(who, r.email(), r.telegram(), r.onAssigned(), r.onGates(), r.onPlans());
    }

    @PostMapping("/api/notifications/telegram/link")
    Map<String, String> link(@AuthenticationPrincipal AuthenticatedMember who) {
        return Map.of("url", service.telegramLink(who));
    }

    @DeleteMapping("/api/notifications/telegram")
    NotificationService.View unlink(@AuthenticationPrincipal AuthenticatedMember who) {
        return service.unlinkTelegram(who);
    }

    @PostMapping("/api/notifications/test")
    Map<String, List<Channel.Kind>> test(@AuthenticationPrincipal AuthenticatedMember who) {
        return Map.of("channels", service.test(who));
    }

    /** Telegram entrega aquí los mensajes al bot; solo con el secreto que se le dio al registrar el webhook. */
    @PostMapping("/api/telegram/webhook")
    ResponseEntity<Void> telegram(@RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret, @RequestBody JsonNode update) {
        if (!bot.configured() || secret == null || !java.security.MessageDigest.isEqual(secret.getBytes(), bot.webhookSecret().getBytes())) {
            return ResponseEntity.status(401).build();
        }
        webhook.handle(update);
        return ResponseEntity.ok().build();
    }
}
