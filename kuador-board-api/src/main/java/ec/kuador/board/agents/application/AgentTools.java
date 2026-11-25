package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.BoardAgentModel;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.sync.CodeReviewApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Las herramientas del tablero que recibe un agente con su pedido: leer el código del proyecto en GitHub (los PR, sus
 * cambios, el CI y los archivos) y repartir una épica en tareas. Las arma {@link AgentBrief}; las ejecuta el modelo.
 */
class AgentTools {

    private static final Logger log = LoggerFactory.getLogger(BoardAgent.class);

    private final CodeReviewApi code;
    private final IssueApi issues;
    private final TransactionTemplate tx;

    AgentTools(CodeReviewApi code, IssueApi issues, TransactionTemplate tx) {
        this.code = code;
        this.issues = issues;
        this.tx = tx;
    }

    /** Los repos del proyecto; si GitHub no responde, ninguno (el agente trabaja sin las herramientas de código). */
    List<String> repositories(String projectKey) {
        try {
            return code.repositories(projectKey);
        } catch (RuntimeException e) {
            log.warn("No se pudieron leer los repos de {}: {}", projectKey, e.getMessage());
            return List.of();
        }
    }

    /** Leer el código del proyecto en GitHub, como lo haría QA en su PC pero sin clonar. */
    List<BoardAgentModel.BoardTool> codeTools(String projectKey) {
        Map<String, Object> listParams = Map.of("type", "object", "properties", Map.of(
                "search", Map.of("type", "string", "description", "Texto a buscar en título, rama o descripción (p. ej. la clave de la tarjeta). Vacío = los últimos.")));
        Map<String, Object> pullParams = Map.of("type", "object", "required", List.of("number"), "properties", Map.of(
                "repository", Map.of("type", "string", "description", "owner/repo (opcional si el proyecto tiene uno solo)."),
                "number", Map.of("type", "integer", "description", "Número del PR.")));
        Map<String, Object> fileParams = Map.of("type", "object", "required", List.of("path"), "properties", Map.of(
                "repository", Map.of("type", "string", "description", "owner/repo (opcional si el proyecto tiene uno solo)."),
                "path", Map.of("type", "string", "description", "Ruta del archivo en el repo."),
                "ref", Map.of("type", "string", "description", "Rama o commit (p. ej. la rama del PR). Vacío = la rama principal.")));
        return List.of(
                new BoardAgentModel.BoardTool("list_pull_requests", "Lista los Pull Requests de los repos del proyecto, los más recientes primero.", listParams,
                        args -> pullsText(code.pullRequests(projectKey, str(args.get("search"))), str(args.get("search")))),
                new BoardAgentModel.BoardTool("read_pull_request", "Lee un Pull Request: descripción, archivos cambiados con su diff y el resultado del CI del último commit.",
                        pullParams, args -> pullText(code.pullRequest(projectKey, str(args.get("repository")), number(args.get("number"))))),
                new BoardAgentModel.BoardTool("read_repo_file", "Lee un archivo de texto del repo, en una rama o commit.", fileParams,
                        args -> code.file(projectKey, str(args.get("repository")), str(args.get("ref")), str(args.get("path")))));
    }

    static String pullsText(List<CodeReviewApi.PullSummary> pulls, String search) {
        if (pulls.isEmpty()) return "No hay Pull Requests" + (search.isEmpty() ? "." : " que mencionen «" + search + "».");
        StringBuilder sb = new StringBuilder();
        for (CodeReviewApi.PullSummary p : pulls) {
            sb.append("- ").append(p.repository()).append(" #").append(p.number()).append(" «").append(p.title()).append("» ")
                    .append(p.merged() ? "fusionado" : p.state()).append(" · ").append(p.branch()).append(" → ").append(p.baseBranch())
                    .append(" · ").append(p.author()).append(" · ").append(p.url()).append("\n");
        }
        return sb.toString();
    }

    static String pullText(CodeReviewApi.PullDetail d) {
        CodeReviewApi.PullSummary p = d.pull();
        StringBuilder sb = new StringBuilder();
        sb.append(p.repository()).append(" #").append(p.number()).append(" «").append(p.title()).append("» · ").append(p.merged() ? "fusionado" : p.state())
                .append(" · rama ").append(p.branch()).append(" → ").append(p.baseBranch()).append(" · commit ").append(d.headSha()).append("\n");
        if (p.body() != null && !p.body().isBlank()) sb.append("\nDescripción:\n").append(p.body().strip()).append("\n");
        sb.append("\nCI del último commit:\n");
        if (d.checksNote() != null) sb.append(d.checksNote()).append("\n");
        else if (d.checks().isEmpty()) sb.append("Sin checks ni estados (el repo no tiene CI o todavía no corrió).\n");
        for (CodeReviewApi.Check c : d.checks()) {
            sb.append("- ").append(c.name()).append(": ").append(c.conclusion() == null ? c.status() : c.conclusion());
            if (c.summary() != null && !c.summary().isBlank()) sb.append(" — ").append(c.summary());
            sb.append("\n");
        }
        sb.append("\nArchivos cambiados (").append(d.files().size()).append(d.filesTruncated() ? ", algunos diffs recortados" : "").append("):\n");
        for (CodeReviewApi.ChangedFile f : d.files()) {
            sb.append("\n### ").append(f.path()).append(" (").append(f.status()).append(", +").append(f.additions()).append(" −").append(f.deletions()).append(")\n");
            sb.append(f.patch() == null ? "(sin diff: binario o muy grande; léelo con read_repo_file)\n" : "```diff\n" + f.patch() + "\n```\n");
        }
        return sb.toString();
    }

    private static int number(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(str(o).replace("#", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("«number» es el número del PR, p. ej. 6.");
        }
    }
    /** Crea las tareas de la épica, hijas suyas, como lo hace create_tasks del servidor MCP. */
    BoardAgentModel.BoardTool createTasks(MemberView agent, String epicKey, boolean repository, List<String> taskRoles) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("type", "object");
        task.put("required", List.of("title", "role"));
        task.put("properties", Map.of(
                "title", Map.of("type", "string", "description", "Título corto de la tarea."),
                "role", Map.of("type", "string", "description", repository
                        ? "Rol que la trabaja y escribe en el repo (" + String.join(", ", taskRoles) + "). Quien solo planea no:"
                                + " el contrato, el modelo de datos y los ADR van en el plan, y si deben quedar en el repo son la primera tarea."
                        : "Clave del rol del equipo que la trabaja (" + String.join(", ", taskRoles) + ")."),
                "description", Map.of("type", "string", "description", "Qué hacer, archivos o módulos y criterio de terminado."),
                "dependsOn", Map.of("type", "array", "items", Map.of("type", "integer"), "description", "Números (desde 1) de tareas de esta lista que deben terminar antes.")));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("required", List.of("tasks"));
        params.put("properties", Map.of("tasks", Map.of("type", "array", "minItems", 1, "items", task, "description", "Las tareas, en orden.")));
        return new BoardAgentModel.BoardTool("create_tasks",
                "Crea una tarjeta por cada tarea del plan, hija de la épica, con su rol y sus dependencias. Esperan en la primera columna "
                        + "hasta que una persona apruebe el reparto. Si llamas otra vez, reemplaza las que no arrancaron.",
                params, args -> {
                    List<IssueApi.PlannedTask> planned = new ArrayList<>();
                    if (!(args.get("tasks") instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException("«tasks» debe ser una lista con al menos una tarea.");
                    for (Object o : list) {
                        if (!(o instanceof Map<?, ?> t)) throw new IllegalArgumentException("Cada tarea es un objeto {title, role, description, dependsOn}.");
                        List<Integer> deps = new ArrayList<>();
                        if (t.get("dependsOn") instanceof List<?> d) d.forEach(n -> deps.add(n instanceof Number num ? num.intValue() : Integer.parseInt(String.valueOf(n))));
                        planned.add(new IssueApi.PlannedTask(str(t.get("title")), t.get("description") == null ? null : str(t.get("description")), str(t.get("role")), deps));
                    }
                    List<IssueApi.IssueRef> created = tx.execute(s -> issues.createEpicTasks(epicKey, planned, agent.id()));
                    return "Creé " + created.size() + " tarea(s) en " + epicKey + ": "
                            + String.join(", ", created.stream().map(r -> r.key() + " " + r.title()).toList()) + ". Ahora entrega con deliver.";
                });
    }
    private static final java.util.regex.Pattern ARGUMENT = java.util.regex.Pattern.compile("^[a-zA-Z0-9_.-]{1,64}$");

    /** Si los nombres de los argumentos de una herramienta los aceptan todos los proveedores de IA. */
    static boolean validArguments(Map<String, Object> schema) {
        if (schema == null || !(schema.get("properties") instanceof Map<?, ?> props)) return true;
        return props.keySet().stream().allMatch(k -> k != null && ARGUMENT.matcher(String.valueOf(k)).matches());
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().strip();
    }
}
