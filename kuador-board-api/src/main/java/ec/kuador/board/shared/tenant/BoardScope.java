package ec.kuador.board.shared.tenant;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Los tableros que puede ver quien hace la petición. Lo fija el filtro de acceso por tablero para las personas que
 * editan o solo miran (y para la conexión de agentes locales de esas personas). Sin valor = todos los del espacio:
 * dueños, administradores, agentes y los procesos internos.
 */
public final class BoardScope {

    private static final ThreadLocal<Set<String>> CURRENT = new ThreadLocal<>();

    private BoardScope() {}

    public static void set(Set<String> projectKeys) {
        CURRENT.set(Set.copyOf(projectKeys));
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Vacío = sin restricción. */
    public static Optional<Set<String>> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static boolean allows(String projectKey) {
        Set<String> keys = CURRENT.get();
        return keys == null || (projectKey != null && keys.contains(projectKey.toUpperCase(Locale.ROOT)));
    }

    /** Del issue {@code PROJ-12}, si la petición puede ver el tablero {@code PROJ}. */
    public static boolean allowsIssue(String issueKey) {
        return CURRENT.get() == null || (issueKey != null && allows(projectOf(issueKey)));
    }

    /** La clave del tablero de un issue ({@code PROJ-12} → {@code PROJ}). */
    public static String projectOf(String issueKey) {
        int dash = issueKey.lastIndexOf('-');
        return dash > 0 ? issueKey.substring(0, dash) : issueKey;
    }
}
