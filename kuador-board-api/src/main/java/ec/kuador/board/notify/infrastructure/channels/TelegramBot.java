package ec.kuador.board.notify.infrastructure.channels;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cliente mínimo de la Bot API de Telegram. El token lo pone el servidor ({@code BOARD_TELEGRAM_BOT_TOKEN}); el
 * secreto del webhook se deriva del token, así no hay otra variable que cuidar.
 */
@Component
public class TelegramBot implements ec.kuador.board.notify.domain.TelegramGateway {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String token;
    private final String username;
    private final String apiUrl;

    TelegramBot(@Value("${board.telegram.bot-token:}") String token, @Value("${board.telegram.bot-username:}") String username,
            @Value("${board.telegram.api-url:https://api.telegram.org}") String apiUrl) {
        this.token = token == null ? "" : token.strip();
        this.username = username == null ? "" : username.strip().replaceFirst("^@", "");
        this.apiUrl = apiUrl.replaceAll("/+$", "");
    }

    @Override
    public boolean configured() {
        return !token.isEmpty() && !username.isEmpty();
    }

    @Override
    public String username() {
        return username;
    }

    /** Secreto que Telegram manda en cada llamada al webhook (cabecera X-Telegram-Bot-Api-Secret-Token). */
    public String webhookSecret() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("webhook:" + token).getBytes(StandardCharsets.UTF_8))).substring(0, 48);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void sendMessage(long chatId, String text) {
        call("sendMessage", Map.of("chat_id", String.valueOf(chatId), "text", text, "link_preview_options", "{\"is_disabled\":true}"));
    }

    public JsonNode call(String method, Map<String, String> params) {
        String form = params.entrySet().stream().map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl + "/bot" + token + "/" + method))
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
