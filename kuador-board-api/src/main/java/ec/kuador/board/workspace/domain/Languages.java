package ec.kuador.board.workspace.domain;

import ec.kuador.board.shared.error.DomainException;
import java.util.List;

/**
 * Idiomas del espacio. {@code team}: el de las personas (specs, planes, comentarios del tablero y textos
 * que ve el usuario final). {@code code}: el del código (nombres, comentarios, pruebas, commits, PRs y la
 * documentación técnica y de la API). Por defecto, equipo en español y código en inglés.
 */
public record Languages(String team, String code) {

    /** Idiomas soportados (ISO 639-1). */
    public static final List<String> SUPPORTED = List.of("es", "en", "pt");
    public static final Languages DEFAULT = new Languages("es", "en");

    public Languages {
        team = normalize(team, "del equipo");
        code = normalize(code, "del código");
    }

    private static String normalize(String value, String what) {
        String v = value == null ? "" : value.strip().toLowerCase();
        if (!SUPPORTED.contains(v)) throw new DomainException("Elige el idioma " + what + ": español, inglés o portugués.");
        return v;
    }
}
