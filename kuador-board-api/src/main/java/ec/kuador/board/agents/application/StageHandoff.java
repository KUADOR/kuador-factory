package ec.kuador.board.agents.application;

import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Cuando un agente del tablero termina su etapa, el issue pasa a la siguiente (como «deliver» del servidor MCP)
 * en vez de volver a la persona: así la cadena sigue sola. Solo si la etapa actual es del rol del agente; en
 * cualquier otra columna (p. ej. una tarjeta que una persona le asignó en «Por hacer») se la devuelve a la persona.
 */
@Component
class StageHandoff {

    /** A dónde pasa: la columna siguiente y quién la atiende. */
    record Next(String columnName, boolean gate, boolean agents) {}

    private final IssueApi issues;
    private final ProjectApi projects;

    StageHandoff(IssueApi issues, ProjectApi projects) {
        this.issues = issues;
        this.projects = projects;
    }

    /** La etapa siguiente si el agente cubre la actual y se puede avanzar; si no, vacío (se devuelve a la persona). */
    Optional<Next> next(MemberView agent, String issueKey) {
        Optional<IssueApi.IssueContext> found = issues.context(issueKey);
        if (found.isEmpty()) return Optional.empty();
        IssueApi.IssueContext issue = found.get();
        ProjectView project = projects.find(issue.projectKey()).orElse(null);
        if (project == null) return Optional.empty();
        Optional<StageDef> stage = project.stage(issue.status());
        if (stage.isEmpty() || stage.get().gate()) return Optional.empty();
        boolean task = issues.taskInfo(issueKey).map(t -> agent.role().equals(t.workRole())).orElse(false);
        if (!task && !stage.get().roles().contains(agent.role())) return Optional.empty();
        Optional<ColumnDef> next = project.nextAfter(issue.status());
        if (next.isEmpty()) return Optional.empty();
        Optional<StageDef> nextStage = project.stage(next.get().key());
        return Optional.of(new Next(next.get().name(), nextStage.map(StageDef::gate).orElse(false),
                nextStage.map(st -> !st.gate() && !st.roles().isEmpty()).orElse(false)));
    }

    /** Comenta y pasa el issue a la siguiente etapa. En una puerta o una columna sin etapa queda con la persona. */
    void deliver(MemberView agent, MemberView requester, String issueKey, Next next, String comment) {
        issues.deliverAs(issueKey, comment, agent.id());
        if (next.gate()) issues.reassign(issueKey, requester.id(), agent.id()); // le llega el aviso para aprobar
    }

    /** Cierre del comentario según a dónde pasa el issue. */
    static String closing(Next next, String requesterName) {
        if (next.gate()) return "➡️ Lo pasé a «" + next.columnName() + "»: espera tu aprobación" + (requesterName == null ? "" : ", " + requesterName) + ".";
        if (next.agents()) return "➡️ Lo pasé a «" + next.columnName() + "»: sigue el agente de esa etapa.";
        return "➡️ Lo pasé a «" + next.columnName() + "» y te lo asigné" + (requesterName == null ? "" : ", " + requesterName) + ".";
    }
}
