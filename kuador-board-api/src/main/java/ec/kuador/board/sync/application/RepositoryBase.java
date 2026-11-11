package ec.kuador.board.sync.application;

import ec.kuador.board.docs.DocsApi.DocContent;
import ec.kuador.board.sync.domain.RepositoryCreator.File;
import java.util.ArrayList;
import java.util.List;
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

    static List<File> files(String projectName, String projectKey, String description, String agentsMd, List<DocContent> documents) {
        List<File> files = new ArrayList<>();
        files.add(new File("README.md", readme(projectName, projectKey, description, documents)));
        // Si la metodología tiene su propio AGENTS.md, el flujo del tablero ya va dentro de ese documento.
        if (documents.stream().noneMatch(d -> d.path().equals(AGENTS_MD))) files.add(new File(AGENTS_MD, agentsMd));
        files.add(new File(".gitignore", GITIGNORE));
        files.add(new File(".github/workflows/ci.yml", ci(documents)));
        documents.forEach(d -> files.add(new File(d.path(), d.content())));
        return files;
    }

    private static String readme(String name, String key, String description, List<DocContent> documents) {
        StringBuilder r = new StringBuilder("# ").append(name).append("\n\n");
        if (description != null && !description.isBlank()) r.append(description.strip()).append("\n\n");
        r.append("""
                Proyecto **%s** del tablero. Los issues, las etapas y las aprobaciones viven en el tablero; este repo guarda el código y los documentos.

                ## Ramas

                - `develop`: rama de trabajo. Cada issue va en su rama (`feature/%s-<número>-<nombre>`) y entra por Pull Request con el CI en verde.
                - `main`: lo publicado. Solo entra desde `develop` por Pull Request aprobado por una persona.

                ## Agentes

                Los agentes trabajan desde el cliente de IA de cada persona conectado al tablero. Ver `AGENTS.md`.
                """.formatted(key, key));
        if (!documents.isEmpty()) {
            r.append("\n## Documentos\n\n");
            documents.forEach(d -> r.append("- [").append(d.title()).append("](").append(d.path()).append(")\n"));
        }
        return r.toString();
    }

    private static String ci(List<DocContent> documents) {
        String required = documents.stream().map(d -> "\"" + d.path() + "\"").collect(Collectors.joining(" "));
        return """
                # CI mínimo del tablero. Las ramas protegidas exigen el job «ci»: agrega aquí los pasos del stack
                # (compilar, lint, pruebas) cuando el proyecto tenga código.
                name: ci
                on:
                  pull_request:
                    branches: [main, develop]
                  push:
                    branches: [main, develop]
                permissions:
                  contents: read
                jobs:
                  ci:
                    name: ci
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                      - name: Documentos de la metodología
                        run: |
                          for f in %s; do
                            test -f "$f" || { echo "Falta $f"; exit 1; }
                          done
                """.formatted(required.isEmpty() ? "README.md" : required);
    }

    private static final String GITIGNORE = """
            # Secretos y configuración local
            .env
            .env.*
            !.env.example

            # Sistema y editores
            .DS_Store
            .idea/
            .vscode/
            *.iml

            # Dependencias y compilados
            node_modules/
            target/
            build/
            dist/
            .next/
            """;
}
