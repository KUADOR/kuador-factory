package ec.kuador.board.agents.application;

import ec.kuador.board.catalog.ColumnCategory;
import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Cuando un agente del tablero termina su etapa, el issue pasa a la siguiente (como «deliver» del servidor MCP)
 * en vez de volver a la persona: así la cadena sigue sola. En una columna sin etapa pasa a la puerta de revisión
 * que sigue, si la hay; si no, se la devuelve a la persona.
 */
@Component
class StageHandoff {

    /** A dónde pasa: la columna siguiente ({@code column} nula = la siguiente permitida) y quién la atiende. */
    record Next(String column, String columnName, boolean gate, boolean agents) {}

    private final IssueApi issues;
    private final ProjectApi projects;

    StageHandoff(IssueApi issues, ProjectApi projects) {
        this.issues = issues;
        this.projects = projects;
    }

    /**
     * Si el agente terminó su etapa, a dónde pasa el issue; si no, vacío (se devuelve a la persona):
     * - En una etapa de su rol: a la siguiente columna permitida.
     * - En una columna sin etapa (p. ej. «Haciendo» de un tablero de tareas): a la primera etapa que sigue, solo si es
     *   una puerta de revisión; así lo que entrega un agente lo aprueba una persona. Si lo que sigue es trabajo de
     *   otro rol, la tarjeta vuelve a la persona, que decide.
     */
    Optional<Next> next(MemberView agent, String issueKey) {
        Optional<IssueApi.IssueContext> found = issues.context(issueKey);
        if (found.isEmpty()) return Optional.empty();
        IssueApi.IssueContext issue = found.get();
        ProjectView project = projects.find(issue.projectKey()).orElse(null);
        if (project == null) return Optional.empty();
        Optional<ColumnDef> current = project.column(issue.status());
        if (current.isEmpty() || current.get().category() == ColumnCategory.DONE) return Optional.empty();
        Optional<StageDef> stage = project.stage(issue.status());
        if (stage.isEmpty()) return reviewAhead(project, issue.status());
        if (stage.get().gate()) return Optional.empty();
        boolean task = issues.taskInfo(issueKey).map(t -> agent.role().equals(t.workRole())).orElse(false);
        if (!task && !stage.get().roles().contains(agent.role())) return Optional.empty();
        Optional<ColumnDef> next = project.nextAfter(issue.status());
        if (next.isEmpty()) return Optional.empty();
        Optional<StageDef> nextStage = project.stage(next.get().key());
        return Optional.of(new Next(null, next.get().name(), nextStage.map(StageDef::gate).orElse(false),
                nextStage.map(st -> !st.gate() && !st.roles().isEmpty()).orElse(false)));
    }

    private static Optional<Next> reviewAhead(ProjectView project, String status) {
        int from = project.indexOf(status);
        return project.columns().stream().skip(from + 1L)
                .filter(c -> project.stage(c.key()).isPresent())
                .findFirst()
                .filter(c -> project.stage(c.key()).map(StageDef::gate).orElse(false) && project.canMove(status, c.key()))
                .map(c -> new Next(c.key(), c.name(), true, false));
    }

    /** A dónde puede devolver el trabajo el agente de esta etapa: la etapa de trabajo anterior (p. ej. de QA a desarrollo). */
    record Back(String column, String columnName) {}

    /**
     * La etapa de trabajo más cercana antes de la actual (con roles y sin puerta), si el agente está en una etapa de su
     * rol. Vacío en las demás: no hay a dónde devolver.
     */
    Optional<Back> back(MemberView agent, String issueKey) {
        Optional<IssueApi.IssueContext> found = issues.context(issueKey);
        if (found.isEmpty()) return Optional.empty();
        IssueApi.IssueContext issue = found.get();
        ProjectView project = projects.find(issue.projectKey()).orElse(null);
        if (project == null) return Optional.empty();
        Optional<StageDef> stage = project.stage(issue.status());
        if (stage.isEmpty() || stage.get().gate() || !stage.get().roles().contains(agent.role())) return Optional.empty();
        List<ColumnDef> before = project.columns().subList(0, Math.max(0, project.indexOf(issue.status())));
        for (int i = before.size() - 1; i >= 0; i--) {
            ColumnDef c = before.get(i);
            boolean work = project.stage(c.key()).map(st -> !st.gate() && !st.roles().isEmpty()).orElse(false);
            if (work) return Optional.of(new Back(c.key(), c.name()));
        }
        return Optional.empty();
    }

    /** Comenta y pasa el issue a la siguiente etapa. En una puerta o una columna sin etapa queda con la persona. */
    void deliver(MemberView agent, MemberView requester, String issueKey, Next next, String comment) {
        if (next.column() == null) issues.deliverAs(issueKey, comment, agent.id());
        else issues.deliverTo(issueKey, next.column(), comment, agent.id());
        if (next.gate()) issues.reassign(issueKey, requester.id(), agent.id()); // le llega el aviso para aprobar
    }

    /** Cierre del comentario según a dónde pasa el issue. */
    static String closing(Next next, String requesterName, ec.kuador.board.catalog.AgentPolicy.Messages m) {
        String template = next.gate() ? m.toGate() : next.agents() ? m.toAgent() : m.toPerson();
        return ec.kuador.board.catalog.AgentRules.fill(template,
                java.util.Map.of("columna", next.columnName(), "persona", requesterName == null ? "" : requesterName));
    }
}
