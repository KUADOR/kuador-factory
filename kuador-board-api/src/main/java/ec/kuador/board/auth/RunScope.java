package ec.kuador.board.auth;

import java.util.Locale;
import java.util.Optional;

/**
 * Hasta dónde llega la credencial de una ejecución del runner: una tarjeta y un rol. Se guarda en el token como
 * «CLAVE|ROL» y, en cada petición con ese token, queda a mano del servidor MCP ({@link #current()}).
 */
public record RunScope(String issueKey, String role) {

    private static final ThreadLocal<RunScope> CURRENT = new ThreadLocal<>();

    public RunScope {
        issueKey = issueKey.toUpperCase(Locale.ROOT);
        role = role.toUpperCase(Locale.ROOT);
    }

    public String encode() {
        return issueKey + "|" + role;
    }

    public static Optional<RunScope> decode(String scope) {
        if (scope == null) return Optional.empty();
        int bar = scope.indexOf('|');
        return bar <= 0 ? Optional.empty() : Optional.of(new RunScope(scope.substring(0, bar), scope.substring(bar + 1)));
    }

    /** El proyecto de la tarjeta (lo que va antes del último guion de la clave). */
    public String projectKey() {
        int dash = issueKey.lastIndexOf('-');
        return dash > 0 ? issueKey.substring(0, dash) : issueKey;
    }

    public static void set(RunScope scope) {
        CURRENT.set(scope);
    }

    public static Optional<RunScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }
}
