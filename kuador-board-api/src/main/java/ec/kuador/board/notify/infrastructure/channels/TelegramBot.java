package ec.kuador.board.notify.infrastructure.channels;

import ec.kuador.board.notify.domain.TelegramGateway;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Cliente mínimo de la Bot API de Telegram. Cada llamada lleva el token del bot que corresponda. */
@Component
public class TelegramBot implements TelegramGateway {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String apiUrl;

    TelegramBot(@Value("${board.telegram.api-url:https://api.telegram.org}") String apiUrl) {
        this.apiUrl = apiUrl.replaceAll("/+$", "");
    }

    @Override
    public String username(String token) {
        return call(token, "getMe", Map.of()).path("username").asString("");
    }

    @Override
    public void sendMessage(String token, long chatId, String text) {
        call(token, "sendMessage", Map.of("chat_id", String.valueOf(chatId), "text", text, "link_preview_options", "{\"is_disabled\":true}"));
    }

    @Override
    public void setWebhook(String token, String url, String secret) {
        call(token, "setWebhook", Map.of("url", url, "secret_token", secret, "allowed_updates", "[\"message\"]"));
    }

    @Override
    public void deleteWebhook(String token) {
        call(token, "deleteWebhook", Map.of());
    }

    public JsonNode updates(String token, long offset) {
        return call(token, "getUpdates", Map.of("offset", String.valueOf(offset), "timeout", "0", "allowed_updates", "[\"message\"]"));
    }

    JsonNode call(String token, String method, Map<String, String> params) {
        if (token == null || token.isBlank()) throw new TelegramException(0, "Falta el token del bot.");
        String form = params.entrySet().stream().map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl + "/bot" + token.strip() + "/" + method))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode body = json.readTree(res.body());
            if (!body.path("ok").asBoolean(false)) {
                throw new TelegramException(res.statusCode(), body.path("description").asString("Telegram respondió " + res.statusCode()));
            }
            return body.path("result");
        } catch (java.io.IOException e) {
            throw new TelegramException(0, "No se pudo hablar con Telegram: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramException(0, "Interrumpido");
        }
    }

    public static class TelegramException extends RuntimeException {
        public final int status;

        TelegramException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
