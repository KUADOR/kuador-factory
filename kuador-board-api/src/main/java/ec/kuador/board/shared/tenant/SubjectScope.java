package ec.kuador.board.shared.tenant;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * El proyecto sobre el que opera la petición en curso (por hilo), cuando el tablero atiende varios. Un tablero es un
 * equipo y su flujo; su repositorio, sus piezas, sus documentos y su manual de trabajo son de cada proyecto.
 *
 * <p>Lo fijan dos entradas: las pantallas del tablero, con el proyecto elegido en la petición ({@code ?subject=}), y el
 * trabajo sobre una tarjeta (un agente, una herramienta del MCP), con el proyecto de la tarjeta. Sin nada fijado vale el
 * primer proyecto del tablero: así un tablero con un solo proyecto funciona como siempre.
 */
public final class SubjectScope {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private SubjectScope() {}

    public static Optional<String> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Hace {@code work} sobre el proyecto {@code subjectKey} (nulo = el primero del tablero) y restaura el anterior. */
    public static <T> T call(String subjectKey, Supplier<T> work) {
        String previous = CURRENT.get();
        set(subjectKey);
        try {
            return work.get();
        } finally {
            set(previous);
        }
    }

    public static void run(String subjectKey, Runnable work) {
        call(subjectKey, () -> {
            work.run();
            return null;
        });
    }

    /** Para el filtro HTTP: fija el proyecto hasta {@link #clear()}. */
    public static void set(String subjectKey) {
        if (subjectKey == null || subjectKey.isBlank()) CURRENT.remove();
        else CURRENT.set(subjectKey.strip().toUpperCase(java.util.Locale.ROOT));
    }

    public static void clear() {
        CURRENT.remove();
    }
}
