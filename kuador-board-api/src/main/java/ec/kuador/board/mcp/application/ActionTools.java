package ec.kuador.board.mcp.application;

import ec.kuador.board.agents.AgentWorkApi;
import ec.kuador.board.billing.BillingApi;
import ec.kuador.board.docs.DocsApi;
import ec.kuador.board.docs.DocsApi.DocContent;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueApi.IssueContext;
import ec.kuador.board.issue.IssueApi.Progress;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Las herramientas que cambian el tablero, siempre firmadas por el agente y solo en issues que supervisa quien llama:
 * tomar, comentar, entregar, devolver, estimar, consultar a una persona, escribir un documento y repartir una épica.
 */
class ActionTools {

    private final ToolScope scope;
    private final IssueApi issues;
    private final ProjectApi projects;
    private final DocsApi docs;
    private final AgentWorkApi work;
    private final BillingApi billing;

    ActionTools(ToolScope scope, IssueApi issues, ProjectApi projects, DocsApi docs, AgentWorkApi work, BillingApi billing) {
        this.scope = scope;
        this.issues = issues;
        this.projects = projects;
        this.docs = docs;
        this.work = work;
        this.billing = billing;
    }

    Object take(Map<String, String> a, UUID person) {
        IssueContext c = scope.ownIssue(a, person);
        MemberView agent = scope.agent(a);
        Progress p = issues.takeAs(c.key(), agent.id());
        work.started(agent.id(), c.key(), c.projectKey(), person, BoardTools.client());
        return progress(p, agent.name() + " tomó " + c.key() + ".");
    }

    Object comment(Map<String, String> a, UUID person) {
        IssueContext c = scope.ownIssue(a, person);
        MemberView agent = scope.agent(a);
        issues.addComment(c.key(), agent.id(), ToolScope.required(a, "text"));
        return Map.of("ok", true, "message", "Comentario publicado en " + c.key() + " como " + agent.name() + ".");
    }

    Object deliver(Map<String, String> a, UUID person) {
        IssueContext c = scope.ownIssue(a, person);
        MemberView agent = scope.agent(a);
        if ("EPIC".equals(c.type()) && !scope.worksEpicFirst(c, agent) && issues.epicTasks(c.key()).isEmpty()) {
            if (scope.project(c.projectKey()).epicSplitsInto(c.status())) {
                throw new ToolException(c.key() + " es una épica sin tareas: antes de entregarla, crea una tarjeta por cada tarea del plan con create_tasks.");
            }
        }
        Optional<String> notReady = issues.notReadyToDeliver(c.key(), agent.id(), a.get("comment"));
        if (notReady.isPresent()) throw new ToolException(notReady.get());
        Progress p = issues.deliverAs(c.key(), a.get("comment"), agent.id());
        work.finished(agent.id(), c.key(), Optional.ofNullable(a.get("comment")).orElse("Entregó a " + p.statusName() + "."));
        return progress(p, c.key() + " pasó a «" + p.statusName() + "».");
    }

    Object sendBack(Map<String, String> a, UUID person) {
        IssueContext c = scope.ownIssue(a, person);
        MemberView agent = scope.agent(a);
        String reason = ToolScope.required(a, "reason");
        Progress p = issues.sendBack(c.key(), ToolScope.required(a, "column").toUpperCase(Locale.ROOT), reason, agent.id());
        work.finished(agent.id(), c.key(), "Devolvió a " + p.statusName() + ": " + reason);
        return progress(p, c.key() + " volvió a «" + p.statusName() + "».");
    }

    private static final List<Integer> POINTS = List.of(1, 2, 3, 5, 8, 13);

    Object estimate(Map<String, String> a, UUID person) {
        IssueContext c = scope.ownIssue(a, person);
        MemberView agent = scope.agent(a);
        int points;
        try {
            points = Integer.parseInt(ToolScope.required(a, "points").strip());
        } catch (NumberFormatException e) {
            throw new ToolException("«points» debe ser un número: 1, 2, 3, 5, 8 o 13.");
        }
        // La unidad la decide el tablero: puntos (la escala de siempre), horas o nada.
        ec.kuador.board.catalog.EstimateUnit unit = projects.find(c.projectKey()).map(ProjectView::estimate).orElse(ec.kuador.board.catalog.EstimateUnit.POINTS);
        if (unit == ec.kuador.board.catalog.EstimateUnit.NONE) return Map.of("ok", false, "message", "Este tablero no estima sus tarjetas: sigue sin estimar.");
        boolean hours = unit == ec.kuador.board.catalog.EstimateUnit.HOURS;
        if (hours && (points < 1 || points > 100)) throw new ToolException("Este tablero estima en horas: usa un número de 1 a 100.");
        if (!hours && !POINTS.contains(points)) throw new ToolException("Usa la escala 1, 2, 3, 5, 8 o 13.");
        String name = hours ? " horas" : " puntos";
        boolean set = issues.setStoryPointsIfEmpty(c.key(), points, agent.id());
        return Map.of("ok", set, "message", set ? c.key() + " quedó en " + points + name + "."
                : c.key() + " ya tenía " + c.storyPoints() + name + ": no se cambian. Si crees que no corresponde, dilo en un comentario.");
    }

    Object ask(Map<String, String> a, UUID person) {
        IssueContext c = scope.ownIssue(a, person);
        MemberView agent = scope.agent(a);
        String question = ToolScope.required(a, "question");
        issues.addComment(c.key(), agent.id(), "**Necesito una decisión:** " + question);
        issues.reassign(c.key(), person, agent.id());
        work.finished(agent.id(), c.key(), "Consultó: " + question);
        return Map.of("ok", true, "message", c.key() + " quedó asignado a " + scope.name(person) + " con tu pregunta. No sigas con este issue.");
    }

    Object writeDoc(Map<String, String> a, UUID person) {
        IssueContext c = scope.ownIssue(a, person);
        MemberView agent = scope.agent(a);
        String kind = ToolScope.required(a, "type").toUpperCase(Locale.ROOT);
        List<String> allowed = docs.allowedKinds(c.projectKey());
        if (!allowed.contains(kind)) {
            // Despliegue es del proyecto (se actualiza en el PR), no de la metodología: no cuenta como «tipo posible» del issue.
            List<String> own = allowed.stream().filter(k -> !ec.kuador.board.catalog.SoftwareDocuments.growsWithProject(k)).toList();
            throw new ToolException("La metodología del proyecto no pide documentos " + kind + ". Tipos posibles: "
                    + (own.isEmpty() ? "ninguno (el proyecto no tiene metodología)" : String.join(", ", own)) + ".");
        }
        // Un documento del proyecto (la Identidad visual, p. ej.) lo escribe desde una tarjeta solo el rol que lo tiene a cargo.
        Optional<ec.kuador.board.catalog.DocumentDef> projectDoc = scope.methodology(scope.project(c.projectKey()))
                .flatMap(m -> m.documents(ec.kuador.board.catalog.DocumentDef.Scope.PROJECT).stream().filter(d -> d.kind().equals(kind)).findFirst());
        if (projectDoc.isPresent() && !agent.role().equals(projectDoc.get().writtenBy())) {
            throw new ToolException(projectDoc.get().label() + " es un documento del proyecto y lo escribe "
                    + (projectDoc.get().writtenBy() == null ? "una persona" : projectDoc.get().writtenBy())
                    + ". Si tu trabajo lo cambia, dilo en tu entrega (o en docs/changes/" + c.key() + ".md si trabajas en el repo).");
        }
        DocContent d = docs.write(c.projectKey(), projectDoc.isPresent() ? null : c.key(), kind, ToolScope.required(a, "content"), a.get("note"), agent.id());
        return Map.of("ok", true, "message", "Guardé " + d.title() + " (versión " + d.version() + ").", "document", ReadTools.docMeta(d));
    }

    Object createTasks(Map<String, String> a, UUID person) {
        try {
            billing.requireFactory("Repartir una épica en tareas");
        } catch (ec.kuador.board.shared.error.DomainException e) {
            throw new ToolException(e.getMessage());
        }
        IssueContext c = scope.ownIssue(a, person);
        MemberView agent = scope.agent(a);
        if (!agent.id().equals(c.assigneeId())) throw new ToolException("Toma primero " + c.key() + " con take_issue.");
        if (scope.worksEpicFirst(c, agent)) {
            throw new ToolException("Trabajas " + c.key() + " antes que el rol que la planea: no la repartes. Escribe tu documento con write_document y entrega.");
        }
        List<IssueApi.PlannedTask> tasks = new java.util.ArrayList<>();
        try {
            tools.jackson.databind.JsonNode list = JSON.readTree(ToolScope.required(a, "tasks"));
            if (list.isString()) list = JSON.readTree(list.asString()); // por si llegó como texto
            for (tools.jackson.databind.JsonNode t : list) {
                List<Integer> deps = new java.util.ArrayList<>();
                t.path("dependsOn").forEach(d -> deps.add(d.asInt()));
                tasks.add(new IssueApi.PlannedTask(t.path("title").asString(""), t.path("description").asString(null), t.path("role").asString(""), deps));
            }
        } catch (tools.jackson.core.JacksonException e) {
            throw new ToolException("«tasks» debe ser una lista JSON de objetos {title, role, description, dependsOn}.");
        }
        List<IssueApi.IssueRef> created = issues.createEpicTasks(c.key(), tasks, agent.id());
        return Map.of("ok", true, "message", "Creé " + created.size() + " tarea(s) en " + c.key() + ". Ahora entrégala con deliver: una persona revisa el reparto y, al aprobarlo, arrancan.",
                "tasks", created.stream().map(r -> Map.of("key", r.key(), "title", r.title())).toList());
    }

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private static Map<String, Object> progress(Progress p, String message) {
        return Map.of("ok", true, "message", message, "column", p.status(), "stage", p.statusName());
    }
}
