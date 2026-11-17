package ec.kuador.board.notify.infrastructure.channels;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push sin dependencias: cifrado del mensaje (RFC 8291, «aes128gcm» de RFC 8188) y firma VAPID (RFC 8292, ES256).
 * El servicio de push del navegador (Google, Apple, Mozilla) solo ve bytes cifrados: lo descifra el dispositivo.
 */
final class WebPush {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ECParameterSpec P256 = p256();
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();
    private static final Base64.Encoder B64U = Base64.getUrlEncoder().withoutPadding();

    private WebPush() {}

    /** Las claves VAPID del servidor: pública sin comprimir (65 bytes) y privada (32 bytes), en base64url. */
    record Vapid(byte[] publicKey, ECPrivateKey privateKey, String subject) {

        static Vapid of(String publicKey, String privateKey, String subject) {
            byte[] pub = decode(publicKey);
            if (pub.length != 65 || pub[0] != 4) throw new IllegalArgumentException("La clave pública VAPID debe ser un punto P-256 sin comprimir (65 bytes).");
            try {
                ECPrivateKey key = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, decode(privateKey)), P256));
                return new Vapid(pub, key, subject);
            } catch (GeneralSecurityException e) {
                throw new IllegalArgumentException("La clave privada VAPID no es válida.", e);
            }
        }

        String publicKeyBase64() {
            return B64U.encodeToString(publicKey);
        }

        /** Encabezado {@code Authorization} para un endpoint: un JWT ES256 de 12 horas para su origen. */
        String authorization(String endpoint, Instant now) {
            URI uri = URI.create(endpoint);
            String audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
            String header = B64U.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
            String claims = B64U.encodeToString(("{\"aud\":\"" + audience + "\",\"exp\":" + now.plusSeconds(12 * 3600).getEpochSecond()
                    + ",\"sub\":\"" + subject.replace("\"", "") + "\"}").getBytes(StandardCharsets.UTF_8));
            try {
                Signature es256 = Signature.getInstance("SHA256withECDSAinP1363Format");
                es256.initSign(privateKey);
                es256.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
                return "vapid t=" + header + "." + claims + "." + B64U.encodeToString(es256.sign()) + ", k=" + publicKeyBase64();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("No se pudo firmar el token VAPID.", e);
            }
        }
    }

    /** Cifra {@code payload} para la suscripción ({@code p256dh} y {@code auth} en base64url). Devuelve el cuerpo a enviar. */
    static byte[] encrypt(byte[] payload, String p256dh, String auth) {
        try {
            byte[] uaPublic = decode(p256dh);
            byte[] authSecret = decode(auth);
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(P256);
            KeyPair as = gen.generateKeyPair();
            byte[] asPublic = uncompressed((ECPublicKey) as.getPublic());

            KeyAgreement ecdh = KeyAgreement.getInstance("ECDH");
            ecdh.init(as.getPrivate());
            ecdh.doPhase(publicKey(uaPublic), true);
            byte[] shared = ecdh.generateSecret();

            // RFC 8291 §3.4: la clave de entrada mezcla el secreto de autenticación y las dos claves públicas.
            byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
            byte[] ikm = hkdf(authSecret, shared, keyInfo, 32);
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            byte[] cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
            byte[] nonce = hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

            // Un solo registro: el mensaje y el delimitador 0x02 de «último registro», sin relleno.
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = gcm.doFinal(concat(payload, new byte[] {2}));

            ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
            header.put(salt).putInt(4096).put((byte) asPublic.length).put(asPublic);
            return concat(header.array(), ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar el aviso push: " + e.getMessage(), e);
        }
    }

    /** Claves VAPID nuevas (para configurar el servidor): {pública, privada} en base64url. */
    static String[] newVapidKeys() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(P256);
            KeyPair kp = gen.generateKeyPair();
            byte[] d = ((ECPrivateKey) kp.getPrivate()).getS().toByteArray();
            return new String[] {B64U.encodeToString(uncompressed((ECPublicKey) kp.getPublic())), B64U.encodeToString(fixed(d, 32))};
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- Apoyo ----

    /** HKDF-SHA256 (RFC 5869) con un solo bloque de expansión (alcanza hasta 32 bytes). */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        return Arrays.copyOf(mac.doFinal(), length);
    }

    static ECPublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
        if (uncompressed.length != 65 || uncompressed[0] != 4) throw new GeneralSecurityException("La clave p256dh no es un punto P-256 sin comprimir.");
        ECPoint w = new ECPoint(new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33)), new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, P256));
    }

    static byte[] uncompressed(ECPublicKey key) {
        return concat(new byte[] {4}, fixed(key.getW().getAffineX().toByteArray(), 32), fixed(key.getW().getAffineY().toByteArray(), 32));
    }

    /** Un entero sin signo a exactamente {@code size} bytes (quita el 0 de signo o rellena con ceros a la izquierda). */
    private static byte[] fixed(byte[] value, int size) {
        if (value.length == size) return value;
        if (value.length > size) return Arrays.copyOfRange(value, value.length - size, value.length);
        byte[] out = new byte[size];
        System.arraycopy(value, 0, out, size - value.length, value.length);
        return out;
    }

    static byte[] decode(String base64url) {
        return B64.decode(base64url.strip().replace('+', '-').replace('/', '_').replace("=", ""));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private static ECParameterSpec p256() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            return ((ECPublicKey) gen.generateKeyPair().getPublic()).getParams();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("La JVM no soporta P-256.", e);
        }
    }
}
