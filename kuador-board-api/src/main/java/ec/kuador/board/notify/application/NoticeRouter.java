package ec.kuador.board.notify.application;

import ec.kuador.board.agents.GateReviewed;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueApi.IssueContext;
import ec.kuador.board.issue.IssueAssigned;
import ec.kuador.board.issue.IssueCommented;
import ec.kuador.board.issue.IssueMoved;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.notify.domain.NoticeKind;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.tenant.TenantContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decide a quién avisar, después del commit y en otro hilo (un SMTP lento no frena el tablero). Solo lo que necesita
 * a una persona: le asignaron un issue, un issue espera su aprobación en una puerta o un reparto de tareas espera que
 * lo apruebe, o un agente revisor decidió una puerta. Nunca avisa a quien hizo el cambio.
 */
@Component
class NoticeRouter {

    private static final Logger log = LoggerFactory.getLogger(NoticeRouter.class);
    private static final int EXCERPT = 600;

    private final NotificationService notifications;
    private final IssueApi issues;
    private final ProjectApi projects;
    private final MemberApi members;
    private final TransactionTemplate tx;
    private final String webUrl;

    NoticeRouter(NotificationService notifications, IssueApi issues, ProjectApi projects, MemberApi members, PlatformTransactionManager txManager,
            @Value("${board.web-url:http://localhost:3600}") String webUrl) {
        this.notifications = notifications;
        this.issues = issues;
        this.projects = projects;
        this.members = members;
        this.tx = new TransactionTemplate(txManager);
        this.webUrl = webUrl.replaceAll("/+$", "");
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(IssueAssigned e) {
        if (e.toAssigneeId() == null || e.toAssigneeId().equals(e.actorId())) return;
        safely(e.workspaceId(), () -> {
            MemberView to = members.find(e.toAssigneeId()).filter(m -> !m.agent()).orElse(null);
            IssueContext issue = issues.context(e.issueKey()).orElse(null);
            if (to == null || issue == null || !projects.canAccess(to.id(), e.projectKey())) return;
            Optional<MemberView> actor = Optional.ofNullable(e.actorId()).flatMap(members::find);
            String subject;
            String body;
            if (actor.isPresent() && actor.get().agent()) {
                // Un agente se lo pasa a una persona: casi siempre una pregunta o el freno de vueltas atrás. Va el último comentario.
                String last = issue.comments().isEmpty() ? "" : issue.comments().getLast().body();
                subject = e.issueKey() + " · " + actor.get().name() + " te necesita";
                body = "«" + issue.title() + "»\n\n" + excerpt(last);
            } else {
                subject = e.issueKey() + " · te lo asignaron";
                body = "«" + issue.title() + "»" + actor.map(a -> "\n\nTe lo asignó " + a.name() + ".").orElse("");
            }
            notifications.enqueue(e.workspaceId(), to.id(), NoticeKind.ASSIGNED, subject, body, link(e.projectKey(), e.issueKey()));
            notifications.enqueueGroups(e.workspaceId(), NoticeKind.ASSIGNED, e.issueKey() + " · ahora lo tiene " + to.name(), body, link(e.projectKey(), e.issueKey()));
        });
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(IssueMoved e) {
        safely(e.workspaceId(), () -> {
            ProjectView project = projects.find(e.projectKey()).orElse(null);
            IssueContext issue = issues.context(e.issueKey()).orElse(null);
            if (project == null || issue == null) return;
            Optional<StageDef> stage = project.stage(e.toStatus());
            String column = project.column(e.toStatus()).map(c -> c.name()).orElse(e.toStatus());
            if (stage.isPresent() && stage.get().gate()) {
                // La revisa un agente: avisa él con su decisión (o, si escala, la asignación a una persona).
                if (issues.agentReviews(e.issueKey())) return;
                String body = "«" + issue.title() + "» llegó a «" + column + "». Apruébalo o recházalo con un motivo desde el tablero.";
                for (UUID person : approvers(project, stage.get(), e.issueKey(), e.actorId())) {
                    notifications.enqueue(e.workspaceId(), person, NoticeKind.GATE, e.issueKey() + " · espera tu aprobación en «" + column + "»", body,
                            link(e.projectKey(), e.issueKey()));
                }
                notifications.enqueueGroups(e.workspaceId(), NoticeKind.GATE, e.issueKey() + " · espera aprobación en «" + column + "»", body,
                        link(e.projectKey(), e.issueKey()));
                return;
            }
            if ("EPIC".equals(issue.type()) && stage.isPresent() && !stage.get().roles().isEmpty()) {
                String first = project.columns().getFirst().key();
                List<IssueApi.TaskInfo> waiting = issues.epicTasks(e.issueKey()).stream().filter(t -> t.status().equals(first)).toList();
                if (waiting.isEmpty() || issue.assigneeId() != null) return;
                StringBuilder list = new StringBuilder();
                waiting.forEach(t -> list.append("\n• ").append(t.key()).append(" ").append(t.title()).append(" (").append(t.workRole()).append(")"));
                String body = "«" + issue.title() + "» llegó a «" + column + "». Revisa el reparto y apruébalo o pide cambios:" + list;
                for (UUID person : supervisorOrOwners(e.projectKey(), e.issueKey(), e.actorId())) {
                    notifications.enqueue(e.workspaceId(), person, NoticeKind.PLAN, e.issueKey() + " · el reparto de " + waiting.size() + " tareas espera tu aprobación",
                            body, link(e.projectKey(), e.issueKey()));
                }
                notifications.enqueueGroups(e.workspaceId(), NoticeKind.PLAN, e.issueKey() + " · el reparto de " + waiting.size() + " tareas espera aprobación",
                        body, link(e.projectKey(), e.issueKey()));
            }
        });
    }

    /**
     * Un comentario que nombra a personas con «@» (de una persona o de un agente): les avisa a ellas, salvo a quien lo
     * escribió y a quien no ve el tablero.
     */
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(IssueCommented e) {
        // El evento trae el comienzo del texto: sin «@» y sin cortar («…»), no nombra a nadie.
        if (e.excerpt() == null || e.excerpt().indexOf('@') < 0 && !e.excerpt().endsWith("…")) return;
        safely(e.workspaceId(), () -> {
            String text = issues.commentBody(e.commentId()).orElse(null);
            IssueContext issue = issues.context(e.issueKey()).orElse(null);
            if (text == null || issue == null) return;
            Set<UUID> people = Mentions.of(text, members.humans());
            people.remove(e.actorId());
            people.removeIf(id -> !projects.canAccess(id, e.projectKey()));
            if (people.isEmpty()) return;
            String who = Optional.ofNullable(e.actorId()).flatMap(members::find).map(MemberView::name).orElse("Alguien");
            String subject = e.issueKey() + " · " + who + " te mencionó";
            String body = "«" + issue.title() + "»\n\n" + excerpt(text);
            for (UUID person : people) {
                notifications.enqueue(e.workspaceId(), person, NoticeKind.MENTION, subject, body, link(e.projectKey(), e.issueKey()));
            }
        });
    }

    /**
     * Lo que decidió un agente revisor en una puerta: a quien supervisa la tarjeta (o a los dueños) y a los grupos. Así
     * una puerta en automático no pasa en silencio: se sabe qué se aprobó, qué se fusionó y qué se devolvió y por qué.
     */
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(GateReviewed e) {
        safely(e.workspaceId(), () -> {
            IssueContext issue = issues.context(e.issueKey()).orElse(null);
            if (issue == null) return;
            String what = switch (e.verdict()) {
                case GateReviewed.MERGED -> "aprobó y fusionó";
                case GateReviewed.RETURNED -> "devolvió";
                default -> "aprobó";
            };
            String subject = e.issueKey() + " · " + e.agentName() + " " + what + " en «" + e.gateName() + "»";
            String body = "«" + issue.title() + "»\n\n" + excerpt(e.summary());
            for (UUID person : supervisorOrOwners(e.projectKey(), e.issueKey(), null)) {
                notifications.enqueue(e.workspaceId(), person, NoticeKind.REVIEW, subject, body, link(e.projectKey(), e.issueKey()));
            }
            notifications.enqueueGroups(e.workspaceId(), NoticeKind.REVIEW, subject, body, link(e.projectKey(), e.issueKey()));
        });
    }

    /**
     * Quienes pueden cerrar la puerta. Si la puerta no pide un rol, quien supervisa el issue (o los dueños del espacio).
     * Solo personas que ven el tablero.
     */
    private Set<UUID> approvers(ProjectView project, StageDef gate, String issueKey, UUID actorId) {
        if (gate.approvers().isEmpty()) return supervisorOrOwners(project.key(), issueKey, actorId);
        Set<UUID> people = new LinkedHashSet<>();
        members.humans().stream().filter(m -> gate.approvers().contains(m.role())).map(MemberView::id)
                .filter(id -> projects.canAccess(id, project.key())).forEach(people::add);
        people.remove(actorId);
        return people;
    }

    private Set<UUID> supervisorOrOwners(String projectKey, String issueKey, UUID actorId) {
        Set<UUID> people = new LinkedHashSet<>();
        issues.supervisorOf(issueKey).ifPresentOrElse(people::add,
                () -> members.humans().stream().filter(m -> "OWNER".equals(m.role())).map(MemberView::id).forEach(people::add));
        people.removeIf(id -> !projects.canAccess(id, projectKey));
        people.remove(actorId);
        return people;
    }

    private String link(String projectKey, String issueKey) {
        return webUrl + "/projects/" + projectKey + "?issue=" + issueKey;
    }

    private static String excerpt(String text) {
        String t = text == null ? "" : text.replace("**", "").strip();
        return t.length() > EXCERPT ? t.substring(0, EXCERPT) + "…" : t;
    }

    /** En el espacio del evento y en su propia transacción. Un aviso que falla no debe afectar a nada más. */
    private void safely(UUID workspaceId, Runnable work) {
        try {
            TenantContext.run(workspaceId, () -> tx.executeWithoutResult(s -> work.run()));
        } catch (RuntimeException ex) {
            log.error("No se pudo preparar un aviso: {}", ex.getMessage(), ex);
        }
    }
}
