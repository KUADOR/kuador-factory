package ec.kuador.board.project.domain;

import ec.kuador.board.catalog.ColumnCategory;
import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToLongFunction;

/** Edición del flujo de un proyecto. Las claves son estables: renombrar no las cambia. */
public final class Workflow {

    private Workflow() {}

    /** Columna pedida por quien edita el flujo: {@code key} nulo = columna nueva. */
    public record Draft(String key, String name, ColumnCategory category) {}

    /**
     * Reemplaza el flujo completo por {@code drafts} (en el orden deseado). Conserva las claves
     * existentes, genera claves para las nuevas e impide quitar columnas que aún tienen issues.
     * Las reglas generales de un flujo (nombres, categorías, máximo) son las de {@link ColumnDef}.
     */
    public static List<ColumnDef> replace(List<ColumnDef> current, List<Draft> drafts, ToLongFunction<String> issuesIn) {
        if (drafts == null || drafts.isEmpty()) {
            throw new DomainException("El flujo necesita al menos un paso.");
        }
        Set<String> currentKeys = new HashSet<>(current.stream().map(ColumnDef::key).toList());
        Set<String> used = new HashSet<>();
        List<ColumnDef> result = new ArrayList<>();
        for (Draft d : drafts) {
            String name = d.name() == null ? "" : d.name().strip();
            String key;
            if (d.key() != null) {
                if (!currentKeys.contains(d.key())) {
                    throw new DomainException("El paso " + d.key() + " no existe en este proyecto.");
                }
                key = d.key();
            } else {
                key = uniqueKey(slug(name), currentKeys, used);
            }
            used.add(key);
            result.add(new ColumnDef(key, name, d.category()));
        }
        ColumnDef.validate(result);
        for (ColumnDef removed : current) {
            if (!used.contains(removed.key())) {
                long count = issuesIn.applyAsLong(removed.key());
                if (count > 0) {
                    throw new ConflictException("No se puede quitar «" + removed.name() + "»: tiene " + count
                            + (count == 1 ? " issue. Muévelo" : " issues. Muévelos") + " primero a otro paso.");
                }
            }
        }
        return result;
    }

    /** "Propuesta enviada" → PROPUESTA_ENVIADA. */
    static String slug(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = ascii.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) slug = "COLUMNA";
        if (Character.isDigit(slug.charAt(0))) slug = "C_" + slug;
        return slug.length() > 26 ? slug.substring(0, 26) : slug;
    }

    // Una clave quitada no se reutiliza en la misma edición: issues o eventos antiguos podrían apuntar a ella.
    private static String uniqueKey(String base, Set<String> existing, Set<String> used) {
        String key = base;
        for (int i = 2; existing.contains(key) || used.contains(key); i++) {
            key = base + "_" + i;
        }
        return key;
    }
}
