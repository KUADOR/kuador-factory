package ec.kuador.board.mcp.application;

import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.Methodology;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueApi.IssueContext;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.tenant.BoardScope;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sobre qué y como quién actúa una llamada: el issue (solo de los tableros que ve quien llama, y para escribir solo los
 * que supervisa), el agente con el que firma y el tablero. Lo que no existe o no le toca sale como {@link ToolException}.
 */
class ToolScope {

    private final IssueApi issues;
    private final ProjectApi projects;
    private final CatalogApi catalog;
    private final MemberApi members;

    ToolScope(IssueApi issues, ProjectApi projects, CatalogApi catalog, MemberApi members) {
        this.issues = issues;
        this.projects = projects;
        this.catalog = catalog;
        this.members = members;
    }

    IssueContext context(Map<String, String> a) {
        String key = required(a, "key").toUpperCase(Locale.ROOT);
        return issues.context(key).filter(c -> BoardScope.allows(c.projectKey()))
                .orElseThrow(() -> new ToolException("No existe el issue " + key + "."));
    }

    /** El proyecto en que se trabaja una tarjeta (nulo si no existe o no se indicó ninguna). */
    String subjectOf(String issueKey) {
        return issueKey == null ? null : issues.find(issueKey).map(IssueApi.IssueRef::subjectKey).orElse(null);
    }

    /** El issue debe estar en la cola de quien llama: sus agentes no trabajan lo que supervisa otra persona. */
    IssueContext ownIssue(Map<String, String> a, UUID person) {
        IssueContext c = context(a);
        Optional<UUID> supervisor = issues.supervisorOf(c.key());
        if (supervisor.isEmpty()) {
            throw new ToolException(c.key() + " no lo supervisa nadie todavía. En el tablero, pulsa «Que lo atiendan mis agentes» en el issue.");
        }
        if (!supervisor.get().equals(person)) {
            throw new ToolException(c.key() + " lo supervisa " + name(supervisor.get()) + ": solo sus agentes lo atienden.");
        }
        return c;
    }

    /** El agente del espacio con ese rol (o ese id). */
    MemberView agent(Map<String, String> a) {
        String value = required(a, "agent").strip();
        List<MemberView> agents = members.agents();
        return agents.stream().filter(m -> m.id().toString().equals(value)).findFirst()
                .or(() -> agents.stream().filter(m -> m.role().equalsIgnoreCase(value)).findFirst())
                .orElseThrow(() -> new ToolException("No hay un agente con el rol " + value + " en el equipo. Roles disponibles: "
                        + String.join(", ", agents.stream().map(MemberView::role).distinct().toList()) + "."));
    }

    /** Su rol trabaja la épica antes que quienes cubren la etapa (UI/UX antes del Arquitecto, p. ej.): no la planea ni la reparte. */
    boolean worksEpicFirst(IssueContext c, MemberView agent) {
        return "EPIC".equals(c.type()) && project(c.projectKey()).stage(c.status()).map(s -> s.worksEpicsFirst(agent.role())).orElse(false);
    }

    ProjectView project(String key) {
        return projects.find(key).filter(p -> BoardScope.allows(p.key()))
                .orElseThrow(() -> new ToolException("No existe el proyecto " + key + "."));
    }

    Optional<Methodology> methodology(ProjectView project) {
        return project.documents() && project.methodology() != null ? catalog.methodology(project.methodology()) : Optional.empty();
    }

    String name(UUID memberId) {
        return members.find(memberId).map(m -> m.agent() ? m.name() + " (agente)" : m.name()).orElse("miembro eliminado");
    }

    static String required(Map<String, String> a, String name) {
        String v = a.get(name);
        if (v == null || v.isBlank()) throw new ToolException("Falta el argumento «" + name + "».");
        return v;
    }

    static String upper(String v) {
        return v == null || v.isBlank() ? null : v.strip().toUpperCase(Locale.ROOT);
    }
}
