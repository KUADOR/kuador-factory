package ec.kuador.board.github.infrastructure.http;

import ec.kuador.board.shared.error.DomainException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * JWT de la GitHub App (RS256, 9 minutos). GitHub entrega la clave en PKCS#1 («BEGIN RSA PRIVATE
 * KEY»); Java la necesita en PKCS#8, así que se envuelve sin dependencias externas.
 */
final class AppJwt {

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    /** Identificador del algoritmo rsaEncryption con parámetros nulos (DER). */
    private static final byte[] RSA_ALGORITHM = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00};

    private AppJwt() {}

    static String sign(long appId, String pem, Instant now) {
        String header = URL.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        // iat un minuto antes por posibles diferencias de reloj; GitHub acepta hasta 10 minutos de vida.
        String payload = URL.encodeToString(("{\"iat\":" + (now.getEpochSecond() - 60) + ",\"exp\":" + (now.getEpochSecond() + 540)
                + ",\"iss\":\"" + appId + "\"}").getBytes(StandardCharsets.UTF_8));
        try {
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(privateKey(pem));
            rsa.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header + "." + payload + "." + URL.encodeToString(rsa.sign());
        } catch (GeneralSecurityException e) {
            throw new DomainException("La clave privada de la GitHub App no es válida. Vuelve a registrar la app.");
        }
    }

    static PrivateKey privateKey(String pem) throws GeneralSecurityException {
        byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
        if (pem.contains("BEGIN RSA PRIVATE KEY")) der = pkcs1ToPkcs8(der);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** PrivateKeyInfo ::= SEQUENCE { INTEGER 0, AlgorithmIdentifier, OCTET STRING (la clave PKCS#1) }. */
    static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
        byte[] octet = tlv(0x04, pkcs1);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(new byte[] {0x02, 0x01, 0x00});
        body.writeBytes(RSA_ALGORITHM);
        body.writeBytes(octet);
        return tlv(0x30, body.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int n = value.length;
        if (n < 0x80) {
            out.write(n);
        } else if (n <= 0xff) {
            out.write(0x81);
            out.write(n);
        } else if (n <= 0xffff) {
            out.write(0x82);
            out.write(n >> 8);
            out.write(n & 0xff);
        } else {
            out.write(0x83);
            out.write(n >> 16);
            out.write((n >> 8) & 0xff);
            out.write(n & 0xff);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }
}
