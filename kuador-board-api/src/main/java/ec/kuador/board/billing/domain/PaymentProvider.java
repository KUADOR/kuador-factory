package ec.kuador.board.billing.domain;

import java.util.Map;

/**
 * Una pasarela configurada: {@code type} elige el adaptador (p. ej. PAYPAL); {@code credentials} son los campos
 * que ese adaptador pide (ya descifrados). {@code mode}: SANDBOX para probar, LIVE para cobrar.
 */
public record PaymentProvider(String code, String type, String name, Mode mode, Map<String, String> credentials, boolean active, int sortOrder) {

    public enum Mode {
        SANDBOX,
        LIVE
    }

    public String credential(String key) {
        String v = credentials.get(key);
        return v == null ? "" : v.strip();
    }
}
