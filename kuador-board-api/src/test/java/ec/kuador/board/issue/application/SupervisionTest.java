package ec.kuador.board.issue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.IssueApi.QueueItem;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.issue.domain.IssueDetails;
import ec.kuador.board.member.MemberView;
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
 * Fase 2 con el flujo de Software: TODO es una puerta del dueño, IN_PROGRESS la cubre el agente
 * Backend, IN_REVIEW el agente QA. Quien pone a trabajar a los agentes los supervisa.
 */
@ExtendWith(TestTenant.class)
class SupervisionTest {

    private static final UUID DIANA = UUID.randomUUID();
    private static final UUID LUIS = UUID.randomUUID();
    private static final UUID BACKEND = UUID.randomUUID();
    private static final UUID QA = UUID.randomUUID();

    private IssueService service;

    @BeforeEach
    void setUp() {
        InMemoryProjects projects = new InMemoryProjects();
        projects.add("KUA", "SOFTWARE", null);
        projects.stages("KUA", 3,
                new StageDef("TODO", List.of(), true, List.of("OWNER"), "BACKLOG"),
                new StageDef("IN_PROGRESS", List.of("BACKEND", "FRONTEND"), false, List.of(), null),
                new StageDef("IN_REVIEW", List.of("QA"), false, List.of(), null));
        InMemoryMembers members = new InMemoryMembers();
        members.add(new MemberView(DIANA, TestTenant.WORKSPACE, "Diana", false, "OWNER", "#fff", null));
        members.add(new MemberView(LUIS, TestTenant.WORKSPACE, "Luis", false, "DEV", "#fff", null));
        members.add(new MemberView(BACKEND, TestTenant.WORKSPACE, "Agente Backend", true, "BACKEND", "#fff", null));
        members.add(new MemberView(QA, TestTenant.WORKSPACE, "Agente QA", true, "QA", "#fff", null));
        service = new IssueService(new IssueServiceTest.InMemoryIssues(), projects, members, new InMemoryCatalog(), e -> {},
                Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC));
    }

    private Issue issueIn(String status, UUID reporter) {
        return service.create("KUA", new IssueDetails("Login", null, IssueType.STORY, null, null, null, null, null), reporter, status);
    }

    private List<String> queue(UUID person) {
        return service.agentQueue(person).stream().map(QueueItem::key).toList();
    }

    @Test
    void approvingIntoAnAgentStageMakesThePersonTheSupervisorAndTheChainInheritsIt() {
        Issue issue = issueIn("TODO", LUIS);
        assertThat(service.supervisorOf(issue.key())).as("en la puerta no trabajan agentes").isEmpty();

        service.review(issue.key(), true, null, DIANA);
        assertThat(service.supervisorOf(issue.key())).contains(DIANA);
        assertThat(service.agentQueue(DIANA)).singleElement().satisfies(i -> {
            assertThat(i.agentRoles()).containsExactly("BACKEND");
            assertThat(i.assigneeName()).isNull();
        });

        service.takeAs(issue.key(), BACKEND);
        assertThat(service.agentQueue(DIANA)).singleElement().satisfies(i -> assertThat(i.assigneeName()).isEqualTo("Agente Backend"));
        service.deliverAs(issue.key(), "Listo", BACKEND);
        assertThat(service.supervisorOf(issue.key())).as("un agente no cambia quién supervisa").contains(DIANA);
        assertThat(service.agentQueue(DIANA)).singleElement().satisfies(i -> assertThat(i.agentRoles()).containsExactly("QA"));
        assertThat(queue(LUIS)).isEmpty();
    }

    @Test
    void assigningToAnAgentOrCreatingInAnAgentStageAlsoSupervises() {
        Issue created = issueIn("IN_PROGRESS", LUIS);
        assertThat(service.supervisorOf(created.key())).contains(LUIS);

        Issue backlog = issueIn("BACKLOG", DIANA);
        service.assign(backlog.key(), QA, DIANA);
        assertThat(queue(DIANA)).containsExactly(backlog.key());
        service.assign(backlog.key(), LUIS, DIANA);
        assertThat(queue(DIANA)).as("asignado a una persona ya no espera a un agente").isEmpty();
    }

    @Test
    void sendingBackNeedsAPreviousColumnAReasonAndTheAgentThatTookIt() {
        Issue issue = issueIn("IN_REVIEW", DIANA);
        assertThatThrownBy(() -> service.sendBack(issue.key(), "IN_PROGRESS", "Falla el login", QA)).isInstanceOf(ForbiddenException.class);
        service.takeAs(issue.key(), QA);
        assertThatThrownBy(() -> service.sendBack(issue.key(), "DONE", "x", QA)).isInstanceOf(DomainException.class).hasMessageContaining("entrega");
        assertThatThrownBy(() -> service.sendBack(issue.key(), "IN_PROGRESS", " ", QA)).isInstanceOf(DomainException.class);

        var back = service.sendBack(issue.key(), "IN_PROGRESS", "Falla el login con clave vacía", QA);
        assertThat(back.status()).isEqualTo("IN_PROGRESS");
        assertThat(back.assigneeId()).isNull();
        assertThat(service.get(issue.key()).reworkCount()).isEqualTo(1);
        assertThat(service.comments(issue.key())).anyMatch(c -> c.body().startsWith("**Devuelto a En progreso** por Agente QA"));
    }

    @Test
    void aPersonCanTakeOverSupervisionButAnAgentCannot() {
        Issue issue = issueIn("IN_PROGRESS", DIANA);
        service.supervise(issue.key(), LUIS);
        assertThat(queue(LUIS)).containsExactly(issue.key());
        assertThat(queue(DIANA)).isEmpty();
        assertThatThrownBy(() -> service.supervise(issue.key(), BACKEND)).isInstanceOf(ForbiddenException.class);
    }
}
