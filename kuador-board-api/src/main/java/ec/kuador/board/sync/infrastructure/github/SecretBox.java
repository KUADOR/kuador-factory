package ec.kuador.board.sync.infrastructure.github;

import ec.kuador.board.shared.error.DomainException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cifra secretos en reposo (tokens de GitHub) con AES-256-GCM. La clave vive en
 * {@code BOARD_SECRETS_KEY} (32 bytes en base64), nunca en la base. Sin clave, no se pueden guardar
 * tokens. Formato: base64(iv de 12 bytes + texto cifrado con su tag).
 */
@Component
class SecretBox {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    SecretBox(@Value("${board.secrets-key:}") String base64Key) {
        SecretKeySpec parsed = null;
        if (!base64Key.isBlank()) {
            byte[] raw = Base64.getDecoder().decode(base64Key.strip());
            if (raw.length != 32) {
                throw new IllegalStateException("BOARD_SECRETS_KEY debe tener 32 bytes en base64 (openssl rand -base64 32).");
            }
            parsed = new SecretKeySpec(raw, "AES");
        }
        this.key = parsed;
    }

    String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar el secreto", e);
        }
    }

    String decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new DomainException("No se pudo leer el token guardado (¿cambió BOARD_SECRETS_KEY?). Vuelve a vincular el repositorio.");
        }
    }

    private SecretKeySpec requireKey() {
        if (key == null) {
            throw new DomainException("Falta BOARD_SECRETS_KEY en la API: sin ella no se pueden guardar tokens de GitHub.");
        }
        return key;
    }
}
