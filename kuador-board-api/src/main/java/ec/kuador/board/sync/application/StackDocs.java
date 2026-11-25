package ec.kuador.board.sync.application;

import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.ComponentTemplate;
import ec.kuador.board.docs.DocsApi;
import ec.kuador.board.docs.DocsApi.DocContent;
import ec.kuador.board.sync.domain.ComponentRepository;
import ec.kuador.board.sync.domain.ProjectComponent;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Las piezas son la única fuente del stack: el tablero escribe la sección «## Stack» del documento de Arquitectura a
 * partir de ellas (las reales o, antes del repo, las planeadas) y la reescribe cada vez que cambian. Así los documentos
 * de la metodología nunca contradicen lo que se construye; los demás documentos remiten a esa sección.
 */
@Component
class StackDocs {

    static final String KIND = "ARCHITECTURE";
    static final String NOTE = "_Lo escribe el tablero a partir de las piezas del proyecto (pestaña Repositorio → Piezas y vínculo): no lo edites a mano._";
    private static final Pattern SECTION = Pattern.compile("(?m)^##\\s+Stack\\s*$");
    private static final Pattern NEXT = Pattern.compile("(?m)^##\\s");

    /** Una pieza como la ve el stack: {@code where} es su carpeta o su repo. */
    record Line(String name, String kind, String stack, String where, String build, String test, String run) {}

    private final ComponentRepository components;
    private final PiecePlans plans;
    private final CatalogApi catalog;
    private final DocsApi docs;

    StackDocs(ComponentRepository components, PiecePlans plans, CatalogApi catalog, DocsApi docs) {
        this.components = components;
        this.plans = plans;
        this.catalog = catalog;
        this.docs = docs;
    }

    /** Las piezas del proyecto (reales) o, si todavía no hay repo, las planeadas. */
    List<Line> lines(String projectKey) {
        List<ProjectComponent> real = components.findByProject(projectKey);
        if (!real.isEmpty()) {
            return real.stream().map(c -> new Line(c.name(), c.kind(), c.stack(), c.path() == null || c.path().isEmpty() ? c.repoLocation() : c.path() + "/",
                    c.buildCommand(), c.testCommand(), c.runCommand())).toList();
        }
        return plans.find(projectKey).map(p -> planned(p.pieces(), "MULTI_REPO".equals(p.layout()))).orElse(List.of());
    }

    /** Piezas que todavía no existen: el stack y los comandos salen de su plantilla. */
    List<Line> planned(List<ComponentService.PieceSpec> specs, boolean ownRepos) {
        return specs.stream().map(s -> {
            Optional<ComponentTemplate> t = s.templateKey() == null || s.templateKey().isBlank() ? Optional.empty() : catalog.componentTemplate(s.templateKey());
            String where = ownRepos && s.repoName() != null && !s.repoName().isBlank() ? s.repoName().strip() : s.name() + "/";
            return new Line(s.name(), t.map(x -> x.kind().name()).orElse(ComponentTemplate.Kind.OTHER.name()), t.map(ComponentTemplate::stack).orElse("Por definir"),
                    where, t.map(x -> x.commands().build()).orElse(null), t.map(x -> x.commands().test()).orElse(null), t.map(x -> x.commands().run()).orElse(null));
        }).toList();
    }

    /** Reescribe el stack de Arquitectura con las piezas actuales. Devuelve el documento como quedó (vacío si no hay Arquitectura). */
    Optional<DocContent> refresh(String projectKey, UUID actorId) {
        return refresh(projectKey, lines(projectKey), actorId);
    }

    Optional<DocContent> refresh(String projectKey, List<Line> lines, UUID actorId) {
        return refresh(projectKey, lines, actorId, false);
    }

    /**
     * {@code createIfMissing}: en un proyecto que ya existía, si no se aprobó Arquitectura se crean los documentos que
     * faltan desde su plantilla (como al armar un repo nuevo), para que el stack tenga dónde vivir.
     */
    Optional<DocContent> refresh(String projectKey, List<Line> lines, UUID actorId, boolean createIfMissing) {
        Optional<DocContent> architecture = architecture(projectKey);
        if (architecture.isEmpty() && createIfMissing && !lines.isEmpty()) {
            docs.ensureProjectDocuments(projectKey, actorId);
            architecture = architecture(projectKey);
        }
        if (architecture.isEmpty() || lines.isEmpty()) return architecture;
        String updated = apply(architecture.get().content(), lines);
        if (updated.equals(architecture.get().content())) return architecture;
        return Optional.of(docs.write(projectKey, null, KIND, updated, DocsApi.STACK_NOTE, actorId));
    }

    private Optional<DocContent> architecture(String projectKey) {
        return docs.ofProject(projectKey).stream().filter(d -> d.issueKey() == null && KIND.equals(d.kind())).findFirst();
    }

    /** El documento con su sección «## Stack» reemplazada (o agregada tras el título) por la de las piezas. */
    static String apply(String content, List<Line> lines) {
        String section = "## Stack\n\n" + NOTE + "\n\n" + table(lines) + "\n";
        Matcher m = SECTION.matcher(content);
        if (m.find()) {
            Matcher next = NEXT.matcher(content);
            int end = next.find(m.end()) ? next.start() : content.length();
            String after = content.substring(end);
            return content.substring(0, m.start()) + section + (after.isEmpty() ? "" : "\n" + after);
        }
        int firstLineEnd = content.indexOf('\n');
        if (content.startsWith("# ") && firstLineEnd > 0) {
            return content.substring(0, firstLineEnd + 1) + "\n" + section + "\n" + content.substring(firstLineEnd + 1).stripLeading();
        }
        return section + "\n" + content;
    }

    static String table(List<Line> lines) {
        StringBuilder b = new StringBuilder("| Pieza | Tipo | Stack | Dónde | Compilar | Probar | Levantar |\n|---|---|---|---|---|---|---|\n");
        for (Line l : lines) {
            b.append("| ").append(l.name()).append(" | ").append(kindLabel(l.kind())).append(" | ").append(cell(l.stack())).append(" | ")
                    .append(code(l.where())).append(" | ").append(code(l.build())).append(" | ").append(code(l.test())).append(" | ").append(code(l.run()))
                    .append(" |\n");
        }
        return b.toString().stripTrailing();
    }

    private static String kindLabel(String kind) {
        return switch (kind == null ? "" : kind.toUpperCase(Locale.ROOT)) {
            case "BACKEND" -> "Backend";
            case "FRONTEND" -> "Frontend";
            case "MONOLITH" -> "Monolito";
            case "MICROFRONTEND" -> "Microfrontend";
            case "SHELL" -> "Shell";
            case "MOBILE" -> "Móvil";
            case "LIBRARY" -> "Librería";
            default -> "Otra";
        };
    }

    private static String cell(String s) {
        return s == null || s.isBlank() ? "—" : s.replace("|", "\\|");
    }

    private static String code(String s) {
        return s == null || s.isBlank() ? "—" : "`" + s.replace("|", "\\|") + "`";
    }
}
