package ec.kuador.board.notify.infrastructure.channels;

import ec.kuador.board.notify.domain.Channel;
import ec.kuador.board.notify.domain.NotifyStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Notificaciones push (la app instalada o el navegador): el aviso va cifrado al servicio de push del dispositivo, firmado
 * con las claves VAPID del servidor. Sin claves configuradas, el canal no está disponible. Si el dispositivo ya no existe
 * (404/410), su suscripción se borra.
 */
@Component
class PushChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(PushChannel.class);
    private static final int MAX_BODY = 400;

    private final NotifyStore store;
    private final Clock clock;
    private final WebPush.Vapid vapid;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json = new ObjectMapper();

    PushChannel(NotifyStore store, Clock clock, @Value("${board.push.vapid-public-key:}") String publicKey,
            @Value("${board.push.vapid-private-key:}") String privateKey, @Value("${board.push.subject:mailto:no-reply@kuador.com}") String subject) {
        this.store = store;
        this.clock = clock;
        this.vapid = publicKey.isBlank() || privateKey.isBlank() ? null : WebPush.Vapid.of(publicKey, privateKey, subject);
    }

    @Override
    public Kind kind() {
        return Kind.PUSH;
    }

    @Override
    public boolean available() {
        return vapid != null;
    }

    /** La clave pública que el navegador necesita para suscribirse ({@code null} si push no está configurado). */
    String publicKey() {
        return vapid == null ? null : vapid.publicKeyBase64();
    }

    @Override
    public void send(UUID workspaceId, String address, String subject, String body, String link) {
        if (vapid == null) throw new IllegalStateException("Push no está configurado en el servidor.");
        NotifyStore.PushSubscription sub = store.pushSubscription(UUID.fromString(address)).orElse(null);
        if (sub == null) return; // el dispositivo se dio de baja
        String text = body == null ? "" : body.strip();
        if (text.length() > MAX_BODY) text = text.substring(0, MAX_BODY - 1) + "…";
        byte[] payload = json.writeValueAsString(java.util.Map.of("title", subject, "body", text, "url", link == null ? "/decisions" : link))
                .getBytes(StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(sub.endpoint()))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", "86400")
                .header("Urgency", "normal")
                .header("Authorization", vapid.authorization(sub.endpoint(), clock.instant()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(WebPush.encrypt(payload, sub.p256dh(), sub.auth())))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("El servicio de push no respondió: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Envío push interrumpido.", e);
        }
        int status = res.statusCode();
        if (status == 404 || status == 410) {
            store.deletePush(sub.id());
            log.info("Suscripción push {} vencida: se borra.", sub.id());
            return;
        }
        if (status < 200 || status >= 300) throw new IllegalStateException("El servicio de push respondió " + status + ".");
    }
}
