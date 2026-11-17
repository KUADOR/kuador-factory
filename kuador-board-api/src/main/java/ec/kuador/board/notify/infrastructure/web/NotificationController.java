package ec.kuador.board.notify.infrastructure.web;

import ec.kuador.board.notify.application.TelegramBots;
import ec.kuador.board.notify.application.TelegramSetupService;
import ec.kuador.board.notify.domain.TelegramBotConfig;
import ec.kuador.board.notify.domain.TelegramGroup;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final TelegramBots bots;
    private final TelegramSetupService setup;
    private final TelegramWebhook webhook;

    NotificationController(NotificationService service, TelegramBots bots, TelegramSetupService setup, TelegramWebhook webhook) {
        this.service = service;
        this.bots = bots;
        this.setup = setup;
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

    record PushRequest(String endpoint, PushKeys keys) {}

    record PushKeys(String p256dh, String auth) {}

    /** Este dispositivo recibe notificaciones push (el navegador manda su suscripción). */
    @PostMapping("/api/notifications/push")
    NotificationService.View subscribePush(@AuthenticationPrincipal AuthenticatedMember who, @RequestBody PushRequest r,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return service.subscribePush(who, r.endpoint(), r.keys() == null ? null : r.keys().p256dh(), r.keys() == null ? null : r.keys().auth(), userAgent);
    }

    /** Este dispositivo deja de recibirlas. */
    @DeleteMapping("/api/notifications/push")
    NotificationService.View unsubscribePush(@AuthenticationPrincipal AuthenticatedMember who, @RequestBody PushRequest r) {
        return service.unsubscribePush(who, r.endpoint());
    }

    @PostMapping("/api/notifications/test")
    Map<String, List<Channel.Kind>> test(@AuthenticationPrincipal AuthenticatedMember who) {
        return Map.of("channels", service.test(who));
    }

    /** Telegram entrega aquí los mensajes de un bot; solo con el secreto que se le dio al registrar su webhook. */
    @PostMapping({"/api/telegram/webhook", "/api/telegram/webhook/{key}"})
    ResponseEntity<Void> telegram(@PathVariable(required = false) String key,
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String secret, @RequestBody JsonNode update) {
        String botKey = key == null ? TelegramBotConfig.PLATFORM : key;
        var bot = bots.byKey(botKey);
        if (bot.isEmpty() || secret == null
                || !java.security.MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), bot.get().webhookSecret().getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        webhook.handle(update, botKey);
        return ResponseEntity.ok().build();
    }

    // ---- Telegram del espacio (personas que editan) ----

    record BotRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 100) String token) {}

    record GroupRequest(boolean onAssigned, boolean onGates, boolean onPlans) {}

    @GetMapping("/api/workspace/telegram")
    TelegramSetupService.Setup setup() {
        return setup.setup();
    }

    @PutMapping("/api/workspace/telegram")
    TelegramSetupService.Setup connect(@jakarta.validation.Valid @RequestBody BotRequest r) {
        return setup.connect(r.token());
    }

    @DeleteMapping("/api/workspace/telegram")
    TelegramSetupService.Setup disconnect() {
        return setup.disconnect();
    }

    @PostMapping("/api/workspace/telegram/group-code")
    TelegramSetupService.GroupCode groupCode() {
        return setup.groupCode();
    }

    @PutMapping("/api/workspace/telegram/groups/{id}")
    TelegramGroup updateGroup(@PathVariable UUID id, @RequestBody GroupRequest r) {
        return setup.updateGroup(id, r.onAssigned(), r.onGates(), r.onPlans());
    }

    @DeleteMapping("/api/workspace/telegram/groups/{id}")
    ResponseEntity<Void> removeGroup(@PathVariable UUID id) {
        setup.removeGroup(id);
        return ResponseEntity.noContent().build();
    }
}
