package ec.kuador.board.issue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.issue.domain.IssueDetails;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.shared.error.DomainException;
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
 * El reparto de una épica en una fábrica (Diseño → Desarrollo → QA): las tareas arrancan donde se construye, en QA las
 * atiende QA, la épica espera en Desarrollo y cada tarea la hace un rol que escribe en el repo.
 */
@ExtendWith(TestTenant.class)
class EpicFlowTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID ARCHITECT = UUID.randomUUID();
    private static final UUID BACKEND = UUID.randomUUID();
    private static final UUID QA = UUID.randomUUID();

    private IssueService service;

    @BeforeEach
    void setUp() {
        InMemoryProjects projects = new InMemoryProjects();
        projects.add("MED", "SOFTWARE", null); // BACKLOG → TODO → IN_PROGRESS → IN_REVIEW → DONE
        projects.stages("MED", 3,
                new StageDef("TODO", List.of("ARCHITECT"), false, List.of(), null), // Diseño
                new StageDef("IN_PROGRESS", List.of("BACKEND", "FRONTEND"), false, List.of(), null), // Desarrollo
                new StageDef("IN_REVIEW", List.of("QA"), false, List.of(), null)); // QA
        InMemoryMembers members = new InMemoryMembers();
        members.add(new MemberView(OWNER, TestTenant.WORKSPACE, "Diana", false, "OWNER", "#fff", null, ec.kuador.board.member.MemberAccess.OWNER));
        members.add(new MemberView(ARCHITECT, TestTenant.WORKSPACE, "Agente Arquitecto", true, "ARCHITECT", "#fff", null));
        members.add(new MemberView(BACKEND, TestTenant.WORKSPACE, "Agente Backend", true, "BACKEND", "#fff", null));
        members.add(new MemberView(QA, TestTenant.WORKSPACE, "Agente QA", true, "QA", "#fff", null));
        service = new IssueService(new IssueServiceTest.InMemoryIssues(), projects, members, new InMemoryCatalog(), e -> {},
                Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC));
    }

    private Issue card(IssueType type, String column) {
        return service.create("MED", new IssueDetails("Seguridad multi-tenant", null, type, null, null, null, null, null), OWNER, column);
    }

    private List<String> split(String key) {
        return service.createEpicTasks(key, List.of(
                new IssueApi.PlannedTask("Contrato de seguridad", null, "BACKEND", List.of()),
                new IssueApi.PlannedTask("Tenancy y RLS", null, "BACKEND", List.of(1))), ARCHITECT)
                .stream().map(IssueApi.IssueRef::key).toList();
    }

    @Test
    void approvingTheSplitWithTheEpicInQaStartsTheTasksInDevelopmentAndBringsTheEpicBack() {
        Issue epic = card(IssueType.EPIC, "IN_REVIEW"); // como MED-10: la llevaron a QA antes de aprobar el reparto
        List<String> tasks = split(epic.key());

        service.reviewPlan(epic.key(), true, null, OWNER);

        assertThat(tasks).allSatisfy(k -> assertThat(service.get(k).status()).as("arranca en Desarrollo, no en QA").isEqualTo("IN_PROGRESS"));
        assertThat(service.get(epic.key()).status()).as("la épica espera donde se construye").isEqualTo("IN_PROGRESS");
    }

    @Test
    void anEpicWithOpenTasksDoesNotMovePastDevelopment() {
        Issue epic = card(IssueType.EPIC, "IN_PROGRESS");
        split(epic.key());

        assertThatThrownBy(() -> service.move(epic.key(), "IN_REVIEW", 0, OWNER))
                .isInstanceOf(DomainException.class).hasMessageContaining("sigue sola").hasMessageContaining("aprueba el reparto");
    }

    @Test
    void aTaskInQaIsForQaNotForTheRoleThatBuiltIt() {
        Issue epic = card(IssueType.EPIC, "IN_PROGRESS");
        String task = split(epic.key()).getFirst();
        service.reviewPlan(epic.key(), true, null, OWNER);
        assertThat(roles(task)).as("en Desarrollo la hace su rol").containsExactly("BACKEND");

        service.move(task, "IN_REVIEW", 0, OWNER);

        assertThat(roles(task)).as("en QA la atiende QA (MED-12 volvía a Backend)").containsExactly("QA");
    }

    private List<String> roles(String key) {
        return service.openForAgents("MED").stream().filter(q -> q.key().equals(key)).findFirst().orElseThrow().agentRoles();
    }

    @Test
    void aTaskForARoleThatDoesNotWriteCodeIsRejected() {
        Issue epic = card(IssueType.EPIC, "TODO");

        assertThatThrownBy(() -> service.createEpicTasks(epic.key(), List.of(
                new IssueApi.PlannedTask("Contrato OpenAPI y ADR", null, "ARCHITECT", List.of())), ARCHITECT))
                .isInstanceOf(DomainException.class).hasMessageContaining("no escribe en el repo").hasMessageContaining("BACKEND");
    }

    @Test
    void aStoryThatThePlanSplitsBecomesAnEpic() {
        Issue story = card(IssueType.STORY, "TODO"); // como MED-10, creada como historia

        List<String> tasks = split(story.key());

        assertThat(service.get(story.key()).type()).isEqualTo(IssueType.EPIC);
        assertThat(service.epicTasks(story.key())).extracting(IssueApi.TaskInfo::key).containsExactlyElementsOf(tasks);
        assertThat(service.comments(story.key())).anyMatch(c -> c.body().contains("pasa a **épica**"));
    }

    @Test
    void aTaskOfAnEpicCannotBeSplitAgain() {
        Issue epic = card(IssueType.EPIC, "TODO");
        String task = split(epic.key()).getFirst();

        assertThat(service.canSplit(task)).isFalse();
        assertThatThrownBy(() -> split(task)).isInstanceOf(DomainException.class).hasMessageContaining("no es una épica");
    }

    @Test
    void splittingAnEpicThatIsAlreadyInDevelopmentAsksForTheApprovalRightAway() {
        Issue epic = card(IssueType.EPIC, "IN_PROGRESS"); // el arquitecto rehízo el plan con la épica ya en Desarrollo

        split(epic.key());

        assertThat(service.comments(epic.key())).as("no hace falta moverla (eso la llevaría a QA)")
                .anyMatch(c -> c.body().startsWith("**Reparto por aprobar:**"));
    }

    @Test
    void thePlanStatusSaysWhenTheSplitCanBeApproved() {
        Issue epic = card(IssueType.EPIC, "TODO");
        split(epic.key());

        IssueService.PlanStatus inDesign = service.planStatus(epic.key());
        assertThat(inDesign.tasks()).hasSize(2);
        assertThat(inDesign.awaitingApproval()).as("en Diseño todavía no").isFalse();
        assertThat(inDesign.notYet()).contains("El reparto se aprueba cuando");

        service.move(epic.key(), "IN_PROGRESS", 0, OWNER);
        IssueService.PlanStatus inDevelopment = service.planStatus(epic.key());
        assertThat(inDevelopment.awaitingApproval()).isTrue();
        assertThat(inDevelopment.notYet()).isNull();

        service.reviewPlan(epic.key(), true, null, OWNER);
        assertThat(service.planStatus(epic.key()).awaitingApproval()).as("ya aprobado").isFalse();
    }

    @Test
    void anAgentThatTookTheEpicBeforeItWasSplitLetsItGo() {
        Issue epic = card(IssueType.EPIC, "IN_PROGRESS"); // la movieron a Desarrollo mientras el arquitecto repartía
        service.assign(epic.key(), BACKEND, OWNER); // y un agente de Desarrollo la tomó como una tarjeta más

        split(epic.key());

        assertThat(service.get(epic.key()).assigneeId()).as("la trabajan sus tareas").isNull();
        assertThat(service.planStatus(epic.key()).awaitingApproval()).isTrue();
    }
}
