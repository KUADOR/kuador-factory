package ec.kuador.board.sync.domain;

import ec.kuador.board.catalog.DocumentDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dónde viven los documentos en un repositorio, según los patrones de ruta de la metodología del
 * proyecto (catálogo). Reconoce los archivos y los agrupa: con «specs/{feature}/spec.md» la carpeta
 * {@code specs/NNN-nombre} es un grupo (spec, plan y tareas de un mismo issue).
 */
public final class RepoLayout {

    /** {@code group}: unidad de importación. {@code issueScoped}: necesita un issue. */
    public record Match(String kind, String group, boolean issueScoped) {}

    private record Rule(String kind, boolean issueScoped, Pattern regex, String fixedPath, String scanDir, int depth, int groupSegments) {}

    private static final String SEGMENT = "([A-Za-z0-9._-]+)";
    private final List<Rule> rules = new ArrayList<>();

    private RepoLayout(List<DocumentDef> documents) {
        for (DocumentDef d : documents) {
            String pattern = d.pathPattern();
            int placeholder = pattern.indexOf('{');
            if (placeholder < 0) {
                rules.add(new Rule(d.kind(), d.scope() == DocumentDef.Scope.ISSUE, null, pattern, null, 0, 0));
                continue;
            }
            String prefix = pattern.substring(0, placeholder);
            String scanDir = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
            int depth = pattern.substring(placeholder).split("/").length;
            // El grupo llega hasta el segmento del marcador: specs/{feature} o odd/tasks/{task}.md.
            int groupSegments = scanDir.isEmpty() ? 1 : scanDir.split("/").length + 1;
            String regex = "^" + Pattern.quote(prefix) + SEGMENT
                    + Pattern.quote(pattern.substring(pattern.indexOf('}', placeholder) + 1)) + "$";
            rules.add(new Rule(d.kind(), d.scope() == DocumentDef.Scope.ISSUE, Pattern.compile(regex), null, scanDir, depth, groupSegments));
        }
    }

    public static RepoLayout of(List<DocumentDef> documents) {
        return new RepoLayout(documents);
    }

    public Optional<Match> classify(String path) {
        for (Rule r : rules) {
            if (r.fixedPath() != null) {
                if (r.fixedPath().equals(path)) return Optional.of(new Match(r.kind(), path, r.issueScoped()));
                continue;
            }
            Matcher m = r.regex().matcher(path);
            if (m.matches()) {
                String[] parts = path.split("/");
                String group = String.join("/", java.util.Arrays.copyOf(parts, Math.min(r.groupSegments(), parts.length)));
                return Optional.of(new Match(r.kind(), group, r.issueScoped()));
            }
        }
        return Optional.empty();
    }

    /** Todos los archivos del repositorio que corresponden a algún documento de la metodología. */
    public List<String> scan(RepositoryFiles files) {
        Map<String, Boolean> found = new LinkedHashMap<>();
        for (Rule r : rules) {
            if (r.fixedPath() != null) {
                if (files.read(r.fixedPath()).isPresent()) found.put(r.fixedPath(), true);
            } else {
                files.listMarkdown(r.scanDir(), r.depth()).forEach(p -> found.put(p, true));
            }
        }
        return found.keySet().stream().filter(p -> classify(p).isPresent()).sorted().toList();
    }

    /**
     * Título sugerido para el issue al importar: el primer encabezado del archivo, sin prefijos de
     * plantilla ("Feature Specification:", "Spec:"...). Si no hay, el nombre de la carpeta.
     */
    public static String suggestTitle(String group, Optional<String> mainContent) {
        Optional<String> heading = mainContent.flatMap(c -> c.lines().filter(l -> l.startsWith("# ")).findFirst())
                .map(l -> l.substring(2).replaceFirst(
                        "(?iu)^(feature specification|especificación de (la )?funcionalidad|especificación|spec|seguimiento|notas|tracking)\\s*:\\s*", "").strip())
                .filter(t -> !t.isEmpty() && !t.startsWith("["));
        if (heading.isPresent()) return trim(heading.get());
        String name = group.substring(group.lastIndexOf('/') + 1).replaceFirst("\\.md$", "").replaceFirst("^\\d+-", "").replace('-', ' ');
        return trim(name.isEmpty() ? group : Character.toUpperCase(name.charAt(0)) + name.substring(1));
    }

    private static String trim(String title) {
        return title.length() > 200 ? title.substring(0, 200) : title;
    }
}
