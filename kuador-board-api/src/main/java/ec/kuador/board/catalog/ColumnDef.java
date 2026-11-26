package ec.kuador.board.catalog;

import ec.kuador.board.shared.error.DomainException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Una columna de un flujo. {@code key} es estable: renombrar la columna no la cambia. */
public record ColumnDef(String key, String name, ColumnCategory category) {

    public static final int MAX_COLUMNS = 12;
    private static final Pattern KEY = Pattern.compile("^[A-Z][A-Z0-9_]{0,29}$");

    /** Reglas de un flujo completo; las usan las plantillas del catálogo y los flujos de cada proyecto. */
    public static void validate(List<ColumnDef> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new DomainException("El flujo necesita al menos un paso.");
        }
        if (columns.size() > MAX_COLUMNS) {
            throw new DomainException("Máximo " + MAX_COLUMNS + " pasos por flujo.");
        }
        Set<String> keys = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (ColumnDef c : columns) {
            if (c.key() == null || !KEY.matcher(c.key()).matches()) {
                throw new DomainException("Clave de paso no válida: " + c.key() + " (mayúsculas, números o _).");
            }
            if (c.name() == null || c.name().isBlank() || c.name().strip().length() > 40) {
                throw new DomainException("Cada paso necesita un nombre de hasta 40 caracteres.");
            }
            if (c.category() == null) {
                throw new DomainException("Indica la categoría de «" + c.name() + "»: TODO, IN_PROGRESS o DONE.");
            }
            if (!keys.add(c.key())) {
                throw new DomainException("El paso " + c.key() + " aparece dos veces.");
            }
            if (!names.add(c.name().strip().toLowerCase(Locale.ROOT))) {
                throw new DomainException("Hay dos pasos llamados «" + c.name().strip() + "».");
            }
        }
    }
}
