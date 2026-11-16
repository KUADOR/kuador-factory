package ec.kuador.board.sync.application;

import ec.kuador.board.docs.DocsApi.DocContent;
import ec.kuador.board.sync.domain.RepositoryCreator.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ec.kuador.board.catalog.AgentRules;
import ec.kuador.board.catalog.AgentPolicy.RepoTemplates;
import java.util.stream.Collectors;

/**
 * Bases de un repo nuevo: lo que no depende del stack. README con el flujo de ramas, AGENTS.md para los
 * clientes de IA, .gitignore, un CI mínimo (el job «ci» que exigen las ramas protegidas) y los
 * documentos del proyecto que pide la metodología. El código del stack llega después, desde su plantilla.
 */
final class RepositoryBase {

    static final String AGENTS_MD = "AGENTS.md";

    private RepositoryBase() {}

    /** Agrega el flujo del tablero al AGENTS.md de la metodología, como una sección más (títulos un nivel abajo). */
    static String withBoardSection(String methodologyAgents, String agentsMd) {
        String section = agentsMd.strip().lines().map(l -> l.startsWith("#") ? "#" + l : l).collect(Collectors.joining("\n"));
        return methodologyAgents.stripTrailing() + "\n\n" + section + "\n";
    }

    /** Cómo aparece una pieza en el README del repo del proyecto. */
    record PieceLine(String name, String stack, String location, String build, String test, String run) {}

    static List<File> files(RepoTemplates t, String projectName, String projectKey, String description, String agentsMd, List<DocContent> documents) {
        return files(t, projectName, projectKey, description, agentsMd, documents, List.of());
    }

    static List<File> files(RepoTemplates t, String projectName, String projectKey, String description, String agentsMd, List<DocContent> documents,
            List<PieceLine> pieces) {
        List<File> files = new ArrayList<>();
        files.add(new File("README.md", readme(t, projectName, projectKey, description, documents, pieces)));
        // Si la metodología tiene su propio AGENTS.md, el flujo del tablero ya va dentro de ese documento.
        if (documents.stream().noneMatch(d -> d.path().equals(AGENTS_MD))) files.add(new File(AGENTS_MD, agentsMd));
        files.add(new File(".gitignore", t.gitignore()));
        files.add(new File(".github/workflows/ci.yml", ci(t, documents)));
        documents.forEach(d -> files.add(new File(d.path(), d.content())));
        return files;
    }

    /** Bases de un repo que solo tiene una pieza (un repo por pieza): los documentos viven en el repo del proyecto. */
    static List<File> pieceRepoFiles(RepoTemplates t, String projectName, String projectKey, String agentsMd, String projectRepo, PieceLine piece) {
        List<File> files = new ArrayList<>();
        files.add(new File("README.md", pieceReadme(t, projectName, projectKey, projectRepo, piece)));
        files.add(new File(AGENTS_MD, agentsMd));
        files.add(new File(".gitignore", t.gitignore()));
        files.add(new File(".github/workflows/ci.yml", ci(t, List.of())));
        return files;
    }

    /** README de una pieza (si su plantilla no trae uno): stack, comandos y dónde están los documentos. */
    static String pieceReadme(RepoTemplates t, String projectName, String projectKey, String projectRepo, PieceLine piece) {
        StringBuilder commands = new StringBuilder();
        commands(commands, piece);
        String docs = projectRepo == null ? "" : "\nLos documentos del proyecto (constitución, specs, planes) viven en `" + projectRepo + "`.\n";
        return tidy(AgentRules.fill(t.pieceReadme(), Map.of("proyecto", projectName, "pieza", piece.name(), "clave", projectKey,
                "stack", piece.stack() == null ? "" : piece.stack(), "comandos", commands.toString(), "repo_documentos", docs)));
    }

    /** Sin líneas en blanco de más donde una variable quedó vacía. */
    private static String tidy(String text) {
        return text.replaceAll("\n{3,}", "\n\n").stripTrailing() + "\n";
    }

    private static void commands(StringBuilder r, PieceLine p) {
        if (blank(p.build()) && blank(p.test()) && blank(p.run())) return;
        r.append("| Compilar | Probar | Levantar |\n|---|---|---|\n")
                .append("| ").append(code(p.build())).append(" | ").append(code(p.test())).append(" | ").append(code(p.run())).append(" |\n");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String code(String s) {
        return blank(s) ? "—" : "`" + s + "`";
    }

    private static String readme(RepoTemplates t, String name, String key, String description, List<DocContent> documents, List<PieceLine> pieces) {
        StringBuilder piecesText = new StringBuilder();
        if (!pieces.isEmpty()) {
            piecesText.append("\n## Piezas\n\n| Pieza | Stack | Dónde | Compilar | Probar | Levantar |\n|---|---|---|---|---|---|\n");
            for (PieceLine p : pieces) {
                piecesText.append("| ").append(p.name()).append(" | ").append(p.stack()).append(" | `").append(p.location()).append("` | ")
                        .append(code(p.build())).append(" | ").append(code(p.test())).append(" | ").append(code(p.run())).append(" |\n");
            }
        }
        StringBuilder docsText = new StringBuilder();
        if (!documents.isEmpty()) {
            docsText.append("\n## Documentos\n\n");
            documents.forEach(d -> docsText.append("- [").append(d.title()).append("](").append(d.path()).append(")\n"));
        }
        return tidy(AgentRules.fill(t.readme(), Map.of("proyecto", name, "clave", key, "descripcion", description == null ? "" : description.strip(),
                "piezas", piecesText.toString(), "documentos", docsText.toString())));
    }

    private static String ci(RepoTemplates t, List<DocContent> documents) {
        String required = documents.stream().map(d -> "\"" + d.path() + "\"").collect(Collectors.joining(" "));
        return AgentRules.fill(t.ci(), Map.of("archivos", required.isEmpty() ? "README.md" : required));
    }
}
