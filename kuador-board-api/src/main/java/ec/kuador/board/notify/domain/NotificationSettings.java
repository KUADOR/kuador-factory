package ec.kuador.board.notify.domain;

import java.util.UUID;

/**
 * Preferencias de avisos de una persona. {@code telegramChatId} nulo = Telegram sin vincular. Sin fila
 * guardada valen los valores por defecto: correo y todos los avisos encendidos.
 */
public record NotificationSettings(
        UUID memberId, boolean email, boolean telegram, Long telegramChatId, boolean onAssigned, boolean onGates, boolean onPlans) {

    public static NotificationSettings defaults(UUID memberId) {
        return new NotificationSettings(memberId, true, true, null, true, true, true);
    }

    public boolean wants(NoticeKind kind) {
        return switch (kind) {
            case ASSIGNED -> onAssigned;
            case GATE, REVIEW -> onGates;
            case PLAN -> onPlans;
            case BILLING, BUDGET, TEST -> true;
        };
    }

    public NotificationSettings withTelegramChat(Long chatId) {
        return new NotificationSettings(memberId, email, telegram, chatId, onAssigned, onGates, onPlans);
    }
}
