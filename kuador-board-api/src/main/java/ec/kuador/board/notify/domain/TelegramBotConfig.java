package ec.kuador.board.notify.domain;

import java.util.UUID;

/**
 * Un bot de Telegram con el que se avisa: el propio de un espacio ({@code workspaceId}) o el de la plataforma
 * ({@code workspaceId} nulo). {@code key} identifica al bot en su webhook.
 */
public record TelegramBotConfig(UUID workspaceId, String token, String username, String webhookSecret) {

    public static final String PLATFORM = "platform";

    public String key() {
        return workspaceId == null ? PLATFORM : workspaceId.toString();
    }

    public boolean platform() {
        return workspaceId == null;
    }
}
