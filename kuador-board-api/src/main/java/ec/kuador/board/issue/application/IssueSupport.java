package ec.kuador.board.issue.application;

import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.IssueAssigned;
import ec.kuador.board.issue.IssueCommented;
import ec.kuador.board.issue.domain.Comment;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.issue.domain.IssueRepository;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Lo que comparten los casos de uso de una tarjeta: cargarla, saber quién actúa, comentar y avisar de un cambio de
 * asignación. Cada pieza de {@link IssueService} parte de aquí; ninguna abre su propia transacción: corre en la de
 * {@link IssueService}, que es quien las llama.
 */
abstract class IssueSupport {

    /** Lo que el módulo necesita de fuera. */
    record Ports(IssueRepository issues, ProjectApi projects, MemberApi members, CatalogApi catalog, ApplicationEventPublisher events, Clock clock) {}

    final IssueRepository issues;
    final ProjectApi projects;
    final MemberApi members;
    final CatalogApi catalog;
    final ApplicationEventPublisher events;
    final Clock clock;

    IssueSupport(Ports ports) {
        this.issues = ports.issues();
        this.projects = ports.projects();
        this.members = ports.members();
        this.catalog = ports.catalog();
        this.events = ports.events();
        this.clock = ports.clock();
    }

    static final int EXCERPT = 280;

    /**
     * La columna donde se construye: la primera etapa de trabajo que cubre un rol que escribe en el repo (en la fábrica,
     * Desarrollo). Ahí arrancan las tareas del reparto y ahí espera la épica mientras se trabajan.
     */
    Optional<String> buildColumn(ProjectView project) {
        if (!project.repository()) return Optional.empty();
        for (ColumnDef c : project.columns()) {
            Optional<StageDef> st = project.stage(c.key());
            if (st.isPresent() && !st.get().gate() && st.get().roles().stream().anyMatch(this::writesCode)) return Optional.of(c.key());
        }
        return Optional.empty();
    }

    boolean writesCode(String role) {
        return catalog.role(role).map(ec.kuador.board.catalog.AgentRole::worksOnCode).orElse(false);
    }

    Optional<Comment> lastComment(Issue issue) {
        List<Comment> all = issues.findComments(issue);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.getLast());
    }

    /** La persona responsable de un issue: quien lo reportó; si fue un agente, la dueña del espacio. */
    Optional<MemberView> responsibleHuman(Issue issue) {
        return Optional.ofNullable(issue.reporterId()).flatMap(members::find).filter(m -> !m.agent())
                .or(() -> members.humans().stream().filter(m -> "OWNER".equals(m.role())).findFirst())
                .or(() -> members.humans().stream().findFirst());
    }

    Comment saveComment(Issue issue, UUID authorId, String body, Instant at) {
        Comment comment = issues.saveComment(Comment.create(issue.id(), authorId, body, at));
        String excerpt = comment.body().length() > EXCERPT ? comment.body().substring(0, EXCERPT) + "…" : comment.body();
        events.publishEvent(new IssueCommented(UUID.randomUUID(), TenantContext.require(), issue.key(), issue.projectKey(), authorId, at,
                comment.id(), excerpt));
        return comment;
    }

    /** Nombres legibles de roles: los del catálogo; «Dueño del espacio» para OWNER. */
    String roleNames(List<String> keys) {
        return String.join(", ", keys.stream().map(k -> "OWNER".equals(k) ? "Dueño del espacio"
                : catalog.role(k).map(r -> r.name()).orElse(k)).toList());
    }

    void publishAssignmentIfChanged(Issue issue, UUID previous, UUID actorId, Instant now) {
        if (!Objects.equals(previous, issue.assigneeId())) {
            events.publishEvent(new IssueAssigned(UUID.randomUUID(), TenantContext.require(), issue.key(), issue.projectKey(), actorId, now,
                    previous, issue.assigneeId()));
        }
    }

    ProjectView project(String projectKey) {
        return projects.find(projectKey).orElseThrow(() -> new NotFoundException("No existe el proyecto " + projectKey + "."));
    }

    /** El miembro existe y ve el tablero: no se le pasa trabajo a quien no está en él. */
    MemberView requireOnBoard(UUID memberId, String projectKey) {
        MemberView m = requireMember(memberId);
        if (!projects.canAccess(m.id(), projectKey)) throw new DomainException(m.name() + " no está en el tablero " + projectKey + ".");
        return m;
    }

    MemberView requireMember(UUID memberId) {
        return members.find(memberId).orElseThrow(() -> new DomainException("El miembro " + memberId + " no existe."));
    }

    Issue load(String key) {
        return issues.findByKey(key).orElseThrow(() -> new NotFoundException("No existe el issue " + key + "."));
    }

    Instant now() {
        return clock.instant();
    }
}
