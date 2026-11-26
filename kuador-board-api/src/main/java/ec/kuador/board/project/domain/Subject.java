package ec.kuador.board.project.domain;

import ec.kuador.board.shared.error.DomainException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Un proyecto del espacio: aquello sobre lo que se trabaja (un sistema, una marca, un cliente). No tiene flujo ni
 * equipo: eso es del tablero. Se crea una vez y lo atienden los tableros que lo marcan.
 */
public record Subject(UUID id, String key, String name, String description, Instant createdAt) {

    private static final Pattern KEY = Pattern.compile("^[A-Z][A-Z0-9]{1,9}$");

    public Subject {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new DomainException("La clave del proyecto va en mayúsculas, de 2 a 10 caracteres (ej. KUA).");
        }
        if (name == null || name.isBlank() || name.strip().length() > 120) {
            throw new DomainException("El nombre del proyecto es obligatorio y tiene máximo 120 caracteres.");
        }
        name = name.strip();
        description = description == null || description.isBlank() ? null : description.strip();
        if (description != null && description.length() > 2000) {
            throw new DomainException("La descripción del proyecto puede tener hasta 2000 caracteres.");
        }
    }

    public static Subject create(String key, String name, String description, Instant now) {
        return new Subject(UUID.randomUUID(), key == null ? null : key.strip().toUpperCase(Locale.ROOT), name, description, now);
    }

    public Subject withDetails(String newName, String newDescription) {
        return new Subject(id, key, newName, newDescription, createdAt);
    }

    /**
     * Una clave libre a partir del nombre: sus iniciales si son varias palabras («Kuador Médica» → KM) o sus primeras
     * letras («Playtodoo» → PLAY), con un número si ya está tomada.
     */
    public static String keyFor(String name, Predicate<String> taken) {
        String ascii = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        String[] words = ascii.replaceAll("[^A-Z0-9 ]", " ").strip().split("\\s+");
        StringBuilder initials = new StringBuilder();
        for (String w : words) if (!w.isEmpty()) initials.append(w.charAt(0));
        String joined = String.join("", words);
        String base = initials.length() >= 2 ? initials.toString() : joined;
        base = base.replaceAll("^[0-9]+", "");
        if (base.length() < 2) base = (base + "PR").substring(0, 2);
        if (base.length() > 4) base = base.substring(0, 4);
        if (!taken.test(base)) return base;
        for (int n = 2; n < 1000; n++) {
            String candidate = base + n;
            if (!taken.test(candidate)) return candidate;
        }
        throw new DomainException("No se pudo dar una clave al proyecto: escríbela tú.");
    }
}
