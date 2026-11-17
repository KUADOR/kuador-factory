package ec.kuador.board.notify.infrastructure.channels;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** El aviso cifrado lo descifra el navegador (aquí, del lado del navegador, siguiendo RFC 8291) y la firma VAPID se verifica. */
class WebPushTest {

    private static final Base64.Encoder B64U = Base64.getUrlEncoder().withoutPadding();

    @Test
    void theBrowserDecryptsWhatTheServerEncrypts() throws Exception {
        // El navegador: su par de claves y su secreto de autenticación.
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair ua = gen.generateKeyPair();
        byte[] uaPublic = WebPush.uncompressed((ECPublicKey) ua.getPublic());
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);

        String message = "{\"title\":\"KUA-12 · espera tu aprobación\",\"url\":\"https://go.kuador.com/decisions\"}";
        byte[] body = WebPush.encrypt(message.getBytes(StandardCharsets.UTF_8), B64U.encodeToString(uaPublic), B64U.encodeToString(auth));

        // Encabezado aes128gcm: sal (16) · tamaño de registro (4) · largo de la clave (1) · clave efímera del servidor (65).
        ByteBuffer b = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        b.get(salt);
        assertThat(b.getInt()).isEqualTo(4096);
        int idLen = b.get();
        assertThat(idLen).isEqualTo(65);
        byte[] asPublic = new byte[idLen];
        b.get(asPublic);
        byte[] ciphertext = new byte[b.remaining()];
        b.get(ciphertext);

        KeyAgreement ecdh = KeyAgreement.getInstance("ECDH");
        ecdh.init(ua.getPrivate());
        ecdh.doPhase(WebPush.publicKey(asPublic), true);
        byte[] shared = ecdh.generateSecret();
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
        byte[] ikm = WebPush.hkdf(auth, shared, keyInfo, 32);
        byte[] cek = WebPush.hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = WebPush.hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = gcm.doFinal(ciphertext);

        assertThat(plain[plain.length - 1]).as("delimitador de último registro").isEqualTo((byte) 2);
        assertThat(new String(Arrays.copyOf(plain, plain.length - 1), StandardCharsets.UTF_8)).isEqualTo(message);
    }

    @Test
    void theVapidTokenIsSignedForTheEndpointOrigin() throws Exception {
        String[] keys = WebPush.newVapidKeys();
        WebPush.Vapid vapid = WebPush.Vapid.of(keys[0], keys[1], "mailto:avisos@kuador.com");
        String header = vapid.authorization("https://fcm.googleapis.com/fcm/send/abc123", Instant.parse("2026-11-17T12:00:00Z"));

        assertThat(header).startsWith("vapid t=").endsWith(", k=" + keys[0]);
        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(claims).contains("\"aud\":\"https://fcm.googleapis.com\"").contains("\"sub\":\"mailto:avisos@kuador.com\"");

        Signature es256 = Signature.getInstance("SHA256withECDSAinP1363Format");
        es256.initVerify(WebPush.publicKey(Base64.getUrlDecoder().decode(keys[0])));
        es256.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(es256.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }
}
