package ec.kuador.board.issue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.catalog.StageDef.AgentReview;
import ec.kuador.board.catalog.StageDef.ReviewMode;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.domain.Comment;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.issue.domain.IssueDetails;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.shared.error.ForbiddenException;
import ec.kuador.board.testing.InMemoryCatalog;
import ec.kuador.board.testing.InMemoryMembers;
import ec.kuador.board.testing.InMemoryProjects;
import ec.kuador.board.testing.TestTenant;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** La puerta IN_REVIEW («revisión de PR») la revisa un agente con escalamiento; TODO sigue siendo de una persona. */
@ExtendWith(TestTenant.class)
class AgentGateReviewTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID REVIEWER = UUID.randomUUID();
    private static final UUID QA = UUID.randomUUID();
    private static final UUID DEV = UUID.randomUUID();

    private IssueService service;

    @BeforeEach
    void setUp() {
        InMemoryProjects projects = new InMemoryProjects();
        projects.add("KUA", "SOFTWARE", null);
        projects.stages("KUA", 3,
                new StageDef("TODO", List.of(), true, List.of("OWNER"), "BACKLOG"),
                new StageDef("IN_REVIEW", List.of(), true, List.of("OWNER"), "IN_PROGRESS",
                        new AgentReview(ReviewMode.AGENT_ESCALATE, null, List.of("**/db/migration/**"), 400)));
        InMemoryMembers members = new InMemoryMembers();
        members.add(new MemberView(OWNER, TestTenant.WORKSPACE, "Diana", false, "OWNER", "#fff", null));
        members.add(new MemberView(REVIEWER, TestTenant.WORKSPACE, "Agente Revisor de PR", true, StageDef.DEFAULT_REVIEWER, "#fff", null));
        members.add(new MemberView(QA, TestTenant.WORKSPACE, "Agente QA", true, "QA", "#fff", null));
        members.add(new MemberView(DEV, TestTenant.WORKSPACE, "Dani", false, "DEV", "#fff", null));
        service = new IssueService(new IssueServiceTest.InMemoryIssues(), projects, members, new InMemoryCatalog(), e -> {},
                Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC));
    }

    private Issue issueIn(String status) {
        Issue i = service.create("KUA", new IssueDetails("Login", null, IssueType.STORY, null, null, null, null, null), OWNER, status);
        service.supervise(i.key(), OWNER);
        return i;
    }

    private List<String> comments(Issue issue) {
        return service.comments(issue.key()).stream().map(Comment::body).toList();
    }

    @Test
    void theReviewerAgentTakesAndClosesItsGateButNotOthers() {
        Issue pr = issueIn("IN_REVIEW");
        assertThat(service.openForAgents("KUA")).extracting(IssueApi.QueueItem::key).as("la puerta del revisor entra en la cadena").contains(pr.key());
        assertThat(service.openForAgents("KUA").stream().filter(q -> q.key().equals(pr.key())).findFirst().orElseThrow().agentRoles())
                .containsExactly(StageDef.DEFAULT_REVIEWER);

        IssueApi.GateReview g = service.gateReview(pr.key(), REVIEWER).orElseThrow();
        assertThat(g.escalates()).isTrue();
        assertThat(g.backName()).isNotBlank();
        assertThat(service.gateReview(pr.key(), QA)).as("otro agente no la revisa").isEmpty();
        assertThatThrownBy(() -> service.review(pr.key(), true, null, QA)).isInstanceOf(ForbiddenException.class);

        service.reviewAs(pr.key(), true, "CI verde, 3/3 criterios", REVIEWER);
        assertThat(service.get(pr.key()).status()).isEqualTo("DONE");
        assertThat(comments(pr)).anyMatch(c -> c.startsWith("**Aprobado** por Agente Revisor de PR."));

        Issue plan = issueIn("TODO");
        assertThat(service.gateReview(plan.key(), REVIEWER)).as("la puerta de aprobación sigue siendo de una persona").isEmpty();
        assertThatThrownBy(() -> service.review(plan.key(), true, null, REVIEWER)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void escalatingLeavesTheGateToAnApproverWithTheAnalysis() {
        Issue pr = issueIn("IN_REVIEW");
        service.reviewAs(pr.key(), false, "Falla el criterio 2: el login no bloquea tras 5 intentos", REVIEWER);
        assertThat(service.get(pr.key()).status()).as("devuelta a desarrollo").isEqualTo("IN_PROGRESS");

        Issue other = issueIn("IN_REVIEW");
        service.escalate(other.key(), "Toca la migración de pacientes: revisa db/migration/V9.", REVIEWER);
        Issue after = service.get(other.key());
        assertThat(after.status()).isEqualTo("IN_REVIEW");
        assertThat(after.assigneeId()).as("a quien la supervisa y puede aprobar").isEqualTo(OWNER);
        assertThat(comments(other)).anyMatch(c -> c.contains("Revisión para una persona") && c.contains("db/migration/V9"));
        assertThat(service.openForAgents("KUA")).extracting(IssueApi.QueueItem::key).as("ya no vuelve al agente").doesNotContain(other.key());
    }

    @Test
    void aCardCanAskForAPersonOrForTheAgent() {
        Issue pr = issueIn("IN_REVIEW");
        service.setReviewBy(pr.key(), "PERSON", OWNER);
        assertThat(service.gateReview(pr.key(), REVIEWER)).isEmpty();
        assertThat(service.openForAgents("KUA")).extracting(IssueApi.QueueItem::key).doesNotContain(pr.key());

        Issue plan = issueIn("TODO");
        service.setReviewBy(plan.key(), "AGENT", OWNER);
        assertThat(service.gateReview(plan.key(), REVIEWER).orElseThrow().escalates()).as("pedida por la tarjeta: siempre con escalamiento").isTrue();
        assertThatThrownBy(() -> service.setReviewBy(plan.key(), "PERSON", REVIEWER)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void nobodySkipsAGateByMovingOrCreatingPastIt() {
        Issue idea = service.create("KUA", new IssueDetails("Idea", null, IssueType.STORY, null, null, null, null, null), DEV, "BACKLOG");
        assertThatThrownBy(() -> service.move(idea.key(), "IN_PROGRESS", 0, DEV)).as("de Backlog a En curso salta la puerta TODO")
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.move(idea.key(), "DONE", 0, QA)).as("un agente tampoco").isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.create("KUA", new IssueDetails("Atajo", null, IssueType.STORY, null, null, null, null, null), DEV, "IN_PROGRESS"))
                .as("ni creándola ya pasada la puerta").isInstanceOf(ForbiddenException.class);

        service.move(idea.key(), "TODO", 0, DEV);
        assertThat(service.get(idea.key()).status()).as("llegar a la puerta sí").isEqualTo("TODO");
        Issue approved = service.create("KUA", new IssueDetails("Aprobada", null, IssueType.STORY, null, null, null, null, null), OWNER, "IN_PROGRESS");
        assertThat(approved.status()).as("quien aprueba puede").isEqualTo("IN_PROGRESS");
    }

    @Test
    void onlyWhoApprovesAPersonGateCanHandItToTheAgent() {
        Issue plan = issueIn("TODO");
        assertThatThrownBy(() -> service.setReviewBy(plan.key(), "AGENT", DEV)).as("saltaría a quien aprueba")
                .isInstanceOf(ForbiddenException.class).hasMessageContaining("Solo quien aprueba");
        assertThat(service.gateReview(plan.key(), REVIEWER)).isEmpty();
        service.setReviewBy(plan.key(), "PERSON", DEV);
        assertThat(service.get(plan.key()).reviewBy()).as("pedir una persona siempre se puede").isEqualTo("PERSON");
    }

    @Test
    void sensitivePathsMatchGlobs() {
        AgentReview r = new AgentReview(ReviewMode.AGENT_ESCALATE, null, List.of("**/db/migration/**", "src/security/**"), null);
        assertThat(r.sensitive("api/src/main/resources/db/migration/V9__x.sql")).isTrue();
        assertThat(r.sensitive("db/migration/V1.sql")).isTrue();
        assertThat(r.sensitive("src/security/Auth.java")).isTrue();
        assertThat(r.sensitive("src/main/App.java")).isFalse();
        assertThat(new StageDef("X", List.of(), false, List.of(), null, r).review()).as("solo una puerta se revisa").isNull();
    }
}
