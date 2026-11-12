package ec.kuador.board.notify.infrastructure.web;

import tools.jackson.databind.JsonNode;

/** Procesa una actualización de un bot de Telegram (la misma lógica sirve al webhook y a getUpdates). */
public interface TelegramWebhook {

    /** {@code botKey}: «platform» o el id del espacio dueño del bot. */
    void handle(JsonNode update, String botKey);
}
