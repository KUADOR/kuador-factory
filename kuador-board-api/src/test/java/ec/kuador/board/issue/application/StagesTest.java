package ec.kuador.board.issue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.domain.Comment;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.issue.domain.IssueDetails;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
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

/**
 * Fase 1 con el flujo de Software (BACKLOG → TODO → IN_PROGRESS → IN_REVIEW → DONE):
 * TODO es «Aprobación» (puerta del dueño), IN_PROGRESS lo cubren Backend y Frontend, IN_REVIEW QA.
 */
@ExtendWith(TestTenant.class)
class StagesTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID CLIENT = UUID.randomUUID();
    private static final UUID BACKEND = UUID.randomUUID();
    private static final UUID QA = UUID.randomUUID();

    private IssueServiceTest.InMemoryIssues issues;
    private InMemoryProjects projects;
    private IssueService service;

    @BeforeEach
    void setUp() {
        issues = new IssueServiceTest.InMemoryIssues();
        projects = new InMemoryProjects();
        projects.add("KUA", "SOFTWARE", null);
        projects.stages("KUA", 2,
                new StageDef("TODO", List.of(), true, List.of("OWNER"), "BACKLOG"),
                new StageDef("IN_PROGRESS", List.of("BACKEND", "FRONTEND"), false, List.of(), null),
                new StageDef("IN_REVIEW", List.of("QA"), false, List.of(), null));
        InMemoryMembers members = new InMemoryMembers();
        members.add(new MemberView(OWNER, TestTenant.WORKSPACE, "Diana", false, "OWNER", "#fff", null));
        members.add(new MemberView(CLIENT, TestTenant.WORKSPACE, "Cliente", false, "CLIENTE", "#fff", null));
        members.add(new MemberView(BACKEND, TestTenant.WORKSPACE, "Agente Backend", true, "BACKEND", "#fff", null));
        members.add(new MemberView(QA, TestTenant.WORKSPACE, "Agente QA", true, "QA", "#fff", null));
        service = new IssueService(issues, projects, members, new InMemoryCatalog(), e -> {}, Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC));
    }

    private Issue issueIn(String status) {
        return service.create("KUA", new IssueDetails("Login", null, IssueType.STORY, null, null, null, null, null), OWNER, status);
    }

    private List<String> comments(Issue issue) {
        return service.comments(issue.key()).stream().map(Comment::body).toList();
    }

    @Test
    void gatesAreClosedOnlyByPeopleWithTheApproverRole() {
        Issue issue = issueIn("TODO");

        assertThatThrownBy(() -> service.review(issue.key(), true, null, BACKEND)).isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("la cierra una persona, no un agente");
        assertThatThrownBy(() -> service.move(issue.key(), "IN_PROGRESS", 0, BACKEND)).as("tampoco arrastrando").isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.review(issue.key(), true, null, CLIENT)).isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Dueño del espacio");
        assertThatThrownBy(() -> service.review(issue.key(), false, " ", OWNER)).isInstanceOf(DomainException.class).hasMessageContaining("explica");

        Issue approved = service.review(issue.key(), true, "Adelante", OWNER);
        assertThat(approved.status()).isEqualTo("IN_PROGRESS");
        assertThat(approved.assigneeId()).as("queda abierta para quien tenga el rol").isNull();
        assertThat(comments(issue)).anyMatch(c -> c.startsWith("**Aprobado** por Diana."));
    }

    @Test
    void aStageDoesNotLetACardLeaveWithoutWhatItRequires() {
        // «En progreso» exige un archivo; la puerta, además, un documento. Vale igual para personas y agentes.
        projects.stages("KUA", 2,
                new StageDef("TODO", List.of(), true, List.of("OWNER"), "BACKLOG", null, false, List.of("El pedido tiene fecha"),
                        List.of(new ec.kuador.board.catalog.Requirement(ec.kuador.board.catalog.Requirement.Kind.DOCUMENT, "SPEC"))),
                new StageDef("IN_PROGRESS", List.of("BACKEND"), false, List.of(), null, null, false, List.of(),
                        List.of(new ec.kuador.board.catalog.Requirement(ec.kuador.board.catalog.Requirement.Kind.ATTACHMENT, null))));
        java.util.Set<String> withFile = new java.util.HashSet<>();
        java.util.Set<String> withSpec = new java.util.HashSet<>();
        service.setEvidence(List.of(new ec.kuador.board.issue.CardEvidence() {
            @Override
            public boolean hasAttachment(String issueKey) {
                return withFile.contains(issueKey);
            }

            @Override
            public boolean hasDocument(String issueKey, String kind) {
                return "SPEC".equals(kind) && withSpec.contains(issueKey);
            }
        }));

        Issue work = issueIn("IN_PROGRESS");
        service.take(work.key(), BACKEND);
        assertThatThrownBy(() -> service.deliver(work.key(), "Listo", BACKEND)).isInstanceOf(DomainException.class).hasMessageContaining("un archivo adjunto");
        withFile.add(work.key());
        assertThat(service.deliver(work.key(), "Listo", BACKEND).status()).isEqualTo("IN_REVIEW");

        Issue gated = issueIn("TODO");
        assertThatThrownBy(() -> service.review(gated.key(), true, null, OWNER)).isInstanceOf(DomainException.class).hasMessageContaining("el documento SPEC");
        assertThat(service.review(gated.key(), false, "Falta la spec", OWNER).status()).as("rechazar no exige nada").isEqualTo("BACKLOG");
        Issue again = issueIn("TODO");
        withSpec.add(again.key());
        assertThat(service.review(again.key(), true, null, OWNER).status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void rejectingSendsTheIssueBackWithTheReason() {
        Issue issue = issueIn("TODO");
        Issue rejected = service.review(issue.key(), false, "Faltan los criterios de aceptación", OWNER);
        assertThat(rejected.status()).isEqualTo("BACKLOG");
        assertThat(comments(issue)).contains("**Rechazado** por Diana: Faltan los criterios de aceptación");
    }

    @Test
    void onlyTheStageRolesCanTakeAndTheOneWhoTookItDelivers() {
        Issue issue = issueIn("IN_PROGRESS");

        assertThatThrownBy(() -> service.take(issue.key(), QA)).isInstanceOf(ForbiddenException.class).hasMessageContaining("Desarrollo backend");
        assertThat(service.take(issue.key(), BACKEND).assigneeId()).isEqualTo(BACKEND);
        assertThatThrownBy(() -> service.take(issue.key(), OWNER)).isInstanceOf(ConflictException.class).hasMessageContaining("Agente Backend");
        assertThatThrownBy(() -> service.deliver(issue.key(), null, QA)).isInstanceOf(ForbiddenException.class);

        Issue delivered = service.deliver(issue.key(), "Listo con pruebas", BACKEND);
        assertThat(delivered.status()).isEqualTo("IN_REVIEW");
        assertThat(delivered.assigneeId()).isNull();
        assertThat(comments(issue)).contains("Listo con pruebas");
        assertThat(service.take(issue.key(), QA).assigneeId()).isEqualTo(QA);
    }

    @Test
    void anAgentDeliveringIntoAColumnWithoutStageHandsTheIssueToItsSupervisor() {
        Issue issue = issueIn("IN_REVIEW");
        service.supervise(issue.key(), OWNER);
        service.take(issue.key(), QA);

        Issue delivered = service.deliver(issue.key(), "Probado", QA);

        assertThat(delivered.status()).isEqualTo("DONE");
        assertThat(delivered.assigneeId()).as("no queda a nombre del agente").isEqualTo(OWNER);
        assertThat(service.agentQueue(OWNER)).as("sale de los pendientes de los agentes").isEmpty();
    }

    @Test
    void aGateCannotBeDeliveredAndTheLastStageHasNoNext() {
        assertThatThrownBy(() -> service.deliver(issueIn("TODO").key(), null, OWNER)).hasMessageContaining("es una puerta");
        assertThatThrownBy(() -> service.deliver(issueIn("DONE").key(), null, OWNER)).hasMessageContaining("no hay una etapa siguiente");
    }

    @Test
    void tooManyLoopsBetweenQaAndDevelopmentGoToAPerson() {
        Issue issue = issueIn("IN_REVIEW");
        for (int i = 1; i <= 2; i++) {
            service.move(issue.key(), "IN_PROGRESS", 0, QA);
            assertThat(service.get(issue.key()).assigneeId()).as("vuelta " + i + ": sigue abierta").isNull();
            service.move(issue.key(), "IN_REVIEW", 0, BACKEND);
        }
        Issue third = service.move(issue.key(), "IN_PROGRESS", 0, QA);
        assertThat(third.reworkCount()).isEqualTo(3);
        assertThat(third.assigneeId()).as("pasó el límite de 2: a quien lo reportó").isEqualTo(OWNER);
        assertThat(comments(issue)).anyMatch(c -> c.contains("volvió atrás 3 veces") && c.contains("Diana"));
    }
}
