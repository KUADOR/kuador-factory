package ec.kuador.board.shared.tenant;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Espacio de trabajo de la operación en curso (por hilo). Lo fija la autenticación en cada petición
 * y los listeners a partir del evento. Hibernate filtra todas las entidades con {@code @TenantId}
 * por este valor.
 *
 * <p>Fallo seguro: sin espacio fijado se usa {@link #NONE}, que no coincide con ningún registro.
 * Las tareas internas que deben ver todos los espacios lo piden explícitamente con {@link #asRoot}.
 */
public final class TenantContext {

    /** Ningún espacio: las consultas no devuelven nada y los inserts fallan por clave foránea. */
    public static final UUID NONE = new UUID(0L, 0L);
    /** Acceso a todos los espacios, solo para tareas internas (login, arranque, limpieza). */
    public static final UUID ROOT = new UUID(0L, 1L);

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static UUID current() {
        UUID id = CURRENT.get();
        return id == null ? NONE : id;
    }

    /** El espacio real fijado; lanza si no hay uno (p. ej. al publicar un evento). */
    public static UUID require() {
        UUID id = current();
        if (id.equals(NONE) || id.equals(ROOT)) {
            throw new IllegalStateException("No hay un espacio de trabajo en contexto.");
        }
        return id;
    }

    public static <T> T call(UUID workspaceId, Supplier<T> work) {
        UUID previous = CURRENT.get();
        CURRENT.set(workspaceId);
        try {
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static void run(UUID workspaceId, Runnable work) {
        call(workspaceId, () -> {
            work.run();
            return null;
        });
    }

    public static <T> T asRoot(Supplier<T> work) {
        return call(ROOT, work);
    }

    /** Para filtros HTTP: fija el espacio hasta {@link #clear()}. */
    public static void set(UUID workspaceId) {
        CURRENT.set(workspaceId);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
