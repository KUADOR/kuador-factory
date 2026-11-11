package ec.kuador.board.notify.infrastructure.web;

import tools.jackson.databind.JsonNode;

/** Procesa una actualización del bot de Telegram (la misma lógica sirve al webhook y a getUpdates). */
public interface TelegramWebhook {

    void handle(JsonNode update);
}
