package ec.kuador.board.docs.domain;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Resuelve la ruta en el repositorio de un documento a partir del patrón de su metodología.
 * {feature} → NNN-nombre (numeración de Spec Kit); {task} → clave-nombre (p. ej. kua-8-login).
 */
public final class DocumentPaths {

    private DocumentPaths() {}

    /** KUA-8 «Prueba de eventos» con «specs/{feature}/spec.md» → specs/008-prueba-de-eventos/spec.md. */
    public static String forIssue(String pattern, String issueKey, int number, String title) {
        String slug = slug(title);
        return pattern
                .replace("{feature}", "%03d-%s".formatted(number, slug))
                .replace("{task}", issueKey.toLowerCase(Locale.ROOT) + "-" + slug);
    }

    static String slug(String text) {
        String ascii = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "sin-titulo" : slug;
    }
}
