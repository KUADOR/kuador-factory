package ec.kuador.board.mcp.application;

import ec.kuador.board.attachment.AttachmentApi;
import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.DocumentDef;
import ec.kuador.board.docs.DocsApi;
import ec.kuador.board.docs.DocsApi.DocContent;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueApi.IssueContext;
import ec.kuador.board.issue.IssueApi.QueueItem;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.tenant.BoardScope;
import ec.kuador.board.sync.RepositoriesApi;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Las herramientas que solo leen: la cola de quien llama, el contexto completo de un issue, el manual del rol y los documentos. */
class ReadTools {

    private final ToolScope scope;
    private final IssueApi issues;
    private final ProjectApi projects;
    private final DocsApi docs;
    private final CatalogApi catalog;
    private final AgentKit kit;
    private final RepositoriesApi repositories;
    private final FactoryRules rules;
    private final AttachmentApi attachments;

    ReadTools(ToolScope scope, IssueApi issues, ProjectApi projects, DocsApi docs, CatalogApi catalog, AgentKit kit, RepositoriesApi repositories,
            FactoryRules rules, AttachmentApi attachments) {
        this.scope = scope;
        this.issues = issues;
        this.projects = projects;
        this.docs = docs;
        this.catalog = catalog;
        this.kit = kit;
        this.repositories = repositories;
        this.rules = rules;
        this.attachments = attachments;
    }

    Object pending(Map<String, String> a, UUID person) {
        String role = ToolScope.upper(a.get("agent"));
        String project = ToolScope.upper(a.get("project"));
        List<Map<String, Object>> items = issues.agentQueue(person).stream()
                .filter(i -> role == null || i.agentRoles().contains(role))
                .filter(i -> project == null || i.projectKey().equals(project))
                // La persona pudo salir de un tablero con issues que aún supervisa: esos ya no son suyos.
                .filter(i -> BoardScope.allows(i.projectKey()))
                .map(this::queueItem).toList();
        return Map.of("items", items, "total", items.size());
    }

    Map<String, Object> queueItem(QueueItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", i.key());
        m.put("project", i.projectKey());
        m.put("title", i.title());
        m.put("type", i.type());
        m.put("priority", i.priority());
        m.put("column", i.status());
        m.put("stage", i.statusName());
        m.put("state", i.assigneeName() == null ? "por tomar" : "lo tiene " + i.assigneeName());
        m.put("agents", i.agentRoles());
        m.put("subagent", kit.subagentName(i.agentRoles().getFirst()));
        if (i.reworkCount() > 0) m.put("reworkCount", i.reworkCount());
        return m;
    }

    Object issue(Map<String, String> a) {
        IssueContext c = scope.context(a);
        ProjectView project = scope.project(c.projectKey());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", c.key());
        Map<String, Object> board = new LinkedHashMap<>(Map.of("key", project.key(), "name", project.name()));
        // El objetivo del tablero: la brújula del negocio para decidir qué priorizar y qué tan completo hacerlo.
        projects.goal(project.key()).ifPresent(g -> board.put("goal", g));
        m.put("project", board);
        // El tablero atiende varios proyectos: en cuál se trabaja esta tarjeta (sus piezas y documentos son los de ese).
        if (projects.subjectsOf(project.key()).size() > 1) {
            projects.subject(project.key(), c.subjectKey()).ifPresent(sj -> {
                Map<String, Object> on = new LinkedHashMap<>(Map.of("key", sj.key(), "name", sj.name()));
                if (sj.description() != null) on.put("description", sj.description());
                m.put("workingOn", on);
            });
        }
        m.put("title", c.title());
        m.put("description", c.description() == null ? "" : c.description());
        m.put("type", c.type());
        m.put("priority", c.priority());
        if (c.storyPoints() != null) m.put("storyPoints", c.storyPoints());
        Map<String, String> cardFields = issues.cardFields(c.key());
        if (!cardFields.isEmpty()) m.put("fields", cardFields);
        if (!c.labels().isEmpty()) m.put("labels", c.labels());
        if (c.parentKey() != null) m.put("epic", c.parentKey());
        m.put("assignee", c.assigneeId() == null ? null : scope.name(c.assigneeId()));
        m.put("stage", stage(project, c.status()));
        m.put("comments", c.comments().stream().map(x -> Map.of("author", scope.name(x.authorId()), "date", x.createdAt().toString(), "text", x.body())).toList());
        if (project.repository()) {
            List<Map<String, Object>> pieces = repositories.pieces(project.key()).stream().map(ReadTools::piece).toList();
            if (!pieces.isEmpty()) m.put("pieces", pieces);
        }
        List<DocContent> all = docs.ofProject(project.key());
        m.put("issueDocuments", all.stream().filter(d -> c.key().equals(d.issueKey())).map(ReadTools::docMeta).toList());
        List<AttachmentApi.AttachmentInfo> files = attachments.list(c.key());
        if (!files.isEmpty()) {
            m.put("attachments", files.stream().map(f -> Map.of("id", f.id().toString(), "name", f.fileName(), "type", f.contentType(),
                    "sizeBytes", f.sizeBytes(), "by", f.uploadedBy() == null ? "" : scope.name(f.uploadedBy()))).toList());
        }
        if (c.parentKey() != null) {
            List<AttachmentApi.AttachmentInfo> epicFiles = attachments.list(c.parentKey());
            if (!epicFiles.isEmpty()) m.put("epicAttachments", epicFiles.stream().map(f -> Map.of("id", f.id().toString(), "name", f.fileName(),
                    "type", f.contentType(), "sizeBytes", f.sizeBytes())).toList());
        }
        if (!files.isEmpty() || m.containsKey("epicAttachments")) {
            m.put("attachmentsGuide", "Lee los adjuntos con read_attachment antes de trabajar: son parte del pedido (briefs, capturas, diagramas, documentos).");
        }
        m.put("projectDocuments", all.stream().filter(d -> d.issueKey() == null).map(ReadTools::docMeta).toList());
        if (project.repository()) {
            boolean deployment = all.stream().anyMatch(d -> d.issueKey() == null && ec.kuador.board.catalog.Methodology.DEPLOYMENT_KIND.equals(d.kind()));
            m.put("projectRules", ec.kuador.board.catalog.AgentRules.softwareProject(deployment));
        }
        if ("EPIC".equals(c.type())) {
            List<IssueApi.TaskInfo> tasks = issues.epicTasks(c.key());
            project.stage(c.status()).filter(s -> !s.epicFirst().isEmpty()).ifPresent(s -> m.put("epicFirstGuide",
                    "En esta etapa, " + String.join(", ", s.epicFirst()) + " trabaja la épica antes que " + String.join(", ", s.roles())
                            + ": si es tu rol, no la planeas ni la repartes (nada de create_tasks). Escribe tu documento con write_document y entrega:"
                            + " la épica sigue en la misma columna con el rol que viene."));
            m.put("tasks", tasks.stream().map(ReadTools::task).toList());
            m.put("epicGuide", tasks.isEmpty()
                    ? "Es una épica. Quien escribe el plan (arquitecto) termina creando una tarjeta por cada tarea del tasks.md con create_tasks "
                            + "(rol y dependencias) antes de deliver. Una persona revisa y aprueba el reparto; entonces arrancan las tareas, y la épica sigue sola al terminarlas."
                    : "Es una épica con tareas: la trabajan sus tareas, cada una por su rol, cuando una persona aprueba el reparto. Si te la devolvieron con cambios al reparto, "
                            + "lee el comentario, ajusta el plan y vuelve a llamar a create_tasks (reemplaza las tareas que no arrancaron) antes de deliver.");
        }
        issues.taskInfo(c.key()).ifPresent(t -> {
            Map<String, Object> task = new LinkedHashMap<>(task(t));
            task.put("epic", c.parentKey());
            task.put("branch", "feature/" + c.key() + "-" + slug(c.title()));
            task.put("rules", List.of(
                    "Una rama y un PR por tarea: trabaja en «feature/" + c.key() + "-…» desde develop y abre el PR hacia develop con la clave " + c.key() + " en el título.",
                    "Si otros agentes trabajan en paralelo en el mismo repo, usa un git worktree propio para tu rama (git worktree add ../<repo>-" + c.key()
                            + " -b <rama> origin/develop) y bórralo al terminar.",
                    "Si la rama ya existe en GitHub (te devolvieron la tarjeta), trae sus commits antes de seguir (git pull --no-rebase): el "
                            + "tablero pudo haberla actualizado con develop mientras esperaba revisión.",
                    "Haz solo lo de esta tarea; lo demás lo cubren las otras tareas de la épica."));
            m.put("task", task);
        });
        scope.methodology(project).ifPresent(meth -> m.put("methodology", Map.of(
                "name", meth.name(),
                "rules", meth.agentRules(),
                "issueDocuments", meth.documents(DocumentDef.Scope.ISSUE).stream().map(ReadTools::docDef).toList())));
        return m;
    }

    Map<String, Object> stage(ProjectView project, String column) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("column", column);
        m.put("name", project.column(column).map(x -> x.name()).orElse(column));
        project.stage(column).ifPresent(s -> {
            m.put("roles", s.roles());
            if (!s.epicFirst().isEmpty()) m.put("epicsFirst", s.epicFirst());
            m.put("gate", s.gate());
            if (s.gate()) m.put("approvers", s.approvers().isEmpty() ? List.of("cualquier persona") : s.approvers());
            if (s.instructions() != null) m.put("instructions", s.instructions());
            if (!s.checklist().isEmpty()) m.put("checklist", s.checklist());
            if (!s.requires().isEmpty()) {
                m.put("requiredToLeave", s.requires().stream().map(r -> r.kind().name().toLowerCase(Locale.ROOT) + (r.ref() == null ? "" : ":" + r.ref())).toList());
            }
        });
        project.nextAfter(column).ifPresent(n -> m.put("next", Map.of("column", n.key(), "name", n.name())));
        // Las decisiones del paso: según el resultado, a qué paso sigue (lo declara el tablero).
        List<Map<String, String>> paths = project.transitions().paths(column).stream()
                .map(t -> Map.of("when", t.when(), "column", t.to(), "name", project.column(t.to()).map(x -> x.name()).orElse(t.to()))).toList();
        if (!paths.isEmpty()) m.put("paths", paths);
        m.put("columns", project.columns().stream().map(x -> x.key() + " (" + x.name() + ")").toList());
        return m;
    }

    Object manual(Map<String, String> a) {
        MemberView agent = scope.agent(a);
        AgentRole role = catalog.role(agent.role()).orElseThrow(() -> new ToolException("El rol " + agent.role() + " ya no está en el catálogo."));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent", agent.name());
        m.put("role", Map.of("key", role.key(), "name", role.name(), "description", role.description()));
        m.put("instructions", role.instructions());
        if (agent.description() != null && !agent.description().isBlank() && !agent.description().equals(role.description())) {
            m.put("specialty", agent.description());
        }
        m.put("tools", role.tools());
        m.put("languages", rules.languages());
        m.put("factoryRules", rules.forRole(role));
        return m;
    }

    Object readDoc(Map<String, String> a) {
        String kind = ToolScope.required(a, "type").toUpperCase(Locale.ROOT);
        String key = ToolScope.upper(a.get("key"));
        String projectKey = key != null ? scope.context(a).projectKey() : ToolScope.upper(a.get("project"));
        if (projectKey == null) throw new ToolException("Indica «key» (documento del issue) o «project» (documento del proyecto).");
        scope.project(projectKey); // existe y quien llama puede verlo
        DocContent d = docs.ofProject(projectKey).stream()
                .filter(x -> x.kind().equals(kind) && java.util.Objects.equals(x.issueKey(), key))
                .findFirst()
                .orElseThrow(() -> new ToolException("No hay documento " + kind + (key != null ? " en " + key : " del proyecto " + projectKey) + " todavía."));
        Map<String, Object> m = new LinkedHashMap<>(docMeta(d));
        m.put("content", d.content());
        return m;
    }

    private static Map<String, Object> task(IssueApi.TaskInfo t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", t.key());
        m.put("title", t.title());
        m.put("role", t.workRole());
        m.put("stage", t.statusName());
        if (!t.dependsOn().isEmpty()) m.put("dependsOn", t.dependsOn());
        if (!t.pendingDependencies().isEmpty()) m.put("waitingFor", t.pendingDependencies());
        if (t.assigneeName() != null) m.put("assignee", t.assigneeName());
        return m;
    }

    private static String slug(String title) {
        String s = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.length() > 40 ? s.substring(0, 40).replaceAll("-+$", "") : s;
    }

    /** Dónde vive una pieza y cómo se compila, prueba y levanta. */
    private static Map<String, Object> piece(RepositoriesApi.Piece p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name());
        m.put("type", p.kind());
        m.put("stack", p.stack());
        m.put("repo", p.repository());
        m.put("folder", p.path().isEmpty() ? "." : p.path());
        if (p.build() != null) m.put("build", p.build());
        if (p.test() != null) m.put("test", p.test());
        if (p.run() != null) m.put("run", p.run());
        if (p.notes() != null) m.put("notes", p.notes());
        return m;
    }

    static Map<String, Object> docMeta(DocContent d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", d.kind());
        m.put("title", d.title());
        m.put("version", d.version());
        if (d.path() != null) m.put("path", d.path());
        return m;
    }

    private static Map<String, Object> docDef(DocumentDef d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", d.kind());
        m.put("name", d.label());
        m.put("purpose", d.purpose());
        m.put("required", d.required());
        if (d.writtenBy() != null) m.put("writtenBy", d.writtenBy());
        if (d.agentInstructions() != null) m.put("howToWrite", d.agentInstructions());
        return m;
    }
}
