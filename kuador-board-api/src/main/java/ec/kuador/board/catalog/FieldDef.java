package ec.kuador.board.catalog;

import ec.kuador.board.shared.error.DomainException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Un dato propio de un tipo de tarjeta (p. ej. «Monto» en una oportunidad o «Paciente» en una cita). {@code key} es
 * estable (no cambia al renombrar); {@code options} solo en {@link Kind#CHOICE}.
 */
public record FieldDef(String key, String label, Kind kind, List<String> options) {

    public enum Kind {
        TEXT,
        NUMBER,
        MONEY,
        DATE,
        CHOICE
    }

    public static final int MAX_PER_TYPE = 12;
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,29}$");
    private static final int MAX_TEXT = 500;

    public FieldDef {
        options = options == null ? List.of() : options.stream().filter(o -> o != null && !o.isBlank()).map(String::strip).distinct().toList();
        label = label == null ? null : label.strip();
    }

    /** Valida las definiciones de un tipo de tarjeta. */
    public static List<FieldDef> validate(List<FieldDef> fields, String typeLabel) {
        if (fields == null) return List.of();
        if (fields.size() > MAX_PER_TYPE) throw new DomainException("Hasta " + MAX_PER_TYPE + " datos propios por tipo de tarjeta («" + typeLabel + "»).");
        Set<String> seen = new java.util.HashSet<>();
        for (FieldDef f : fields) {
            if (f.key() == null || !KEY.matcher(f.key()).matches()) {
                throw new DomainException("Clave de dato no válida en «" + typeLabel + "»: " + f.key() + " (minúsculas, números o _, hasta 30).");
            }
            if (!seen.add(f.key())) throw new DomainException("El dato " + f.key() + " está dos veces en «" + typeLabel + "».");
            if (f.label() == null || f.label().isBlank() || f.label().length() > 40) throw new DomainException("Cada dato necesita un nombre de hasta 40 caracteres.");
            if (f.kind() == null) throw new DomainException("Indica el tipo del dato «" + f.label() + "».");
            if (f.kind() == Kind.CHOICE && (f.options().isEmpty() || f.options().size() > 20)) {
                throw new DomainException("«" + f.label() + "» es una lista: indica de 1 a 20 opciones.");
            }
            for (String o : f.options()) if (o.length() > 60) throw new DomainException("Una opción de «" + f.label() + "» es demasiado larga.");
        }
        return List.copyOf(fields);
    }

    /**
     * Los valores de una tarjeta para estas definiciones: descarta lo que no está definido y lo vacío, y valida cada
     * valor según su tipo. Se guardan como texto (un número como «1250.50», una fecha como «2026-11-23»).
     */
    public static Map<String, String> clean(List<FieldDef> defs, Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || defs == null) return out;
        for (FieldDef f : defs) {
            String v = raw.get(f.key());
            if (v == null || v.isBlank()) continue;
            out.put(f.key(), f.normalize(v.strip()));
        }
        return out;
    }

    private String normalize(String v) {
        switch (kind) {
            case TEXT -> {
                if (v.length() > MAX_TEXT) throw new DomainException("«" + label + "» admite hasta " + MAX_TEXT + " caracteres.");
                return v;
            }
            case NUMBER, MONEY -> {
                try {
                    BigDecimal n = new BigDecimal(v.replace(",", "."));
                    if (n.precision() - n.scale() > 15 || n.scale() > 6) throw new NumberFormatException();
                    return n.stripTrailingZeros().scale() < 0 ? n.toBigInteger().toString() : n.stripTrailingZeros().toPlainString();
                } catch (NumberFormatException e) {
                    throw new DomainException("«" + label + "» debe ser un número (p. ej. 1250.50).");
                }
            }
            case DATE -> {
                try {
                    return LocalDate.parse(v).toString();
                } catch (DateTimeParseException e) {
                    throw new DomainException("«" + label + "» debe ser una fecha (p. ej. 2026-11-23).");
                }
            }
            case CHOICE -> {
                return options.stream().filter(o -> o.equalsIgnoreCase(v)).findFirst()
                        .orElseThrow(() -> new DomainException("«" + label + "» debe ser una de: " + String.join(", ", options) + "."));
            }
        }
        return v;
    }
}
