package ec.kuador.board.notify.infrastructure.channels;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import ec.kuador.board.notify.domain.NotifyStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Un aviso llega al servicio de push con VAPID y cifrado; el dispositivo lo lee; una suscripción vencida se borra. */
class PushChannelTest {

    private static final Base64.Encoder B64U = Base64.getUrlEncoder().withoutPadding();

    private HttpServer server;
    private int status = 201;
    private final AtomicReference<byte[]> body = new AtomicReference<>();
    private final AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>();
    private final List<UUID> deleted = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push/", ex -> {
            body.set(ex.getRequestBody().readAllBytes());
            headers.set(Map.copyOf(ex.getRequestHeaders()));
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void theDeviceReadsTheNoticeAndAGoneSubscriptionIsRemoved() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair ua = gen.generateKeyPair();
        byte[] uaPublic = WebPush.uncompressed((ECPublicKey) ua.getPublic());
        byte[] auth = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        UUID id = UUID.randomUUID();
        NotifyStore.PushSubscription sub = new NotifyStore.PushSubscription(id, UUID.randomUUID(), UUID.randomUUID(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/push/abc", B64U.encodeToString(uaPublic), B64U.encodeToString(auth));
        String[] vapid = WebPush.newVapidKeys();
        PushChannel channel = new PushChannel(store(sub), Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC), vapid[0], vapid[1],
                "mailto:avisos@kuador.com");

        assertThat(channel.available()).isTrue();
        channel.send(sub.workspaceId(), id.toString(), "KUA-12 · espera tu aprobación", "Revisión de PR", "https://go.kuador.com/projects/KUA?issue=KUA-12");

        assertThat(headers.get().get("Content-encoding")).containsExactly("aes128gcm");
        assertThat(headers.get().get("Authorization").getFirst()).startsWith("vapid t=").endsWith("k=" + vapid[0]);
        String json = decrypt(body.get(), ua, uaPublic, auth);
        assertThat(json).contains("\"title\":\"KUA-12 · espera tu aprobación\"").contains("\"url\":\"https://go.kuador.com/projects/KUA?issue=KUA-12\"");

        status = 410;
        channel.send(sub.workspaceId(), id.toString(), "Otro", "", null);
        assertThat(deleted).as("el dispositivo ya no existe: se borra su suscripción").containsExactly(id);
    }

    @Test
    void withoutVapidKeysPushIsNotAvailable() {
        assertThat(new PushChannel(store(null), Clock.systemUTC(), "", "", "mailto:x@y.z").available()).isFalse();
    }

    private NotifyStore store(NotifyStore.PushSubscription sub) {
        return (NotifyStore) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {NotifyStore.class}, (p, m, args) -> switch (m.getName()) {
            case "pushSubscription" -> Optional.ofNullable(sub);
            case "deletePush" -> {
                if (args.length == 1) deleted.add((UUID) args[0]);
                yield null;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static String decrypt(byte[] message, KeyPair ua, byte[] uaPublic, byte[] auth) throws Exception {
        byte[] salt = Arrays.copyOfRange(message, 0, 16);
        int idLen = message[20];
        byte[] asPublic = Arrays.copyOfRange(message, 21, 21 + idLen);
        byte[] ciphertext = Arrays.copyOfRange(message, 21 + idLen, message.length);
        KeyAgreement ecdh = KeyAgreement.getInstance("ECDH");
        ecdh.init(ua.getPrivate());
        ecdh.doPhase(WebPush.publicKey(asPublic), true);
        java.io.ByteArrayOutputStream info = new java.io.ByteArrayOutputStream();
        info.writeBytes("WebPush: info\0".getBytes(StandardCharsets.US_ASCII));
        info.writeBytes(uaPublic);
        info.writeBytes(asPublic);
        byte[] ikm = WebPush.hkdf(auth, ecdh.generateSecret(), info.toByteArray(), 32);
        byte[] cek = WebPush.hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = WebPush.hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = gcm.doFinal(ciphertext);
        return new String(plain, 0, plain.length - 1, StandardCharsets.UTF_8);
    }
}
