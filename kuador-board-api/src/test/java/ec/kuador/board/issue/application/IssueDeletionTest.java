package ec.kuador.board.issue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.CardEvidence;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Borrar varias tarjetas juntas sin dejar el tablero roto (tareas sueltas, dependencias a la nada, agentes a medias). */
@ExtendWith(TestTenant.class)
class IssueDeletionTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID ARCHITECT = UUID.randomUUID();

    private IssueService service;
    private final Set<String> working = new HashSet<>();

    @BeforeEach
    void setUp() {
        InMemoryProjects projects = new InMemoryProjects();
        projects.add("MED", "SOFTWARE", null); // BACKLOG → TODO → IN_PROGRESS → IN_REVIEW → DONE
        projects.stages("MED", 3,
                new StageDef("TODO", List.of("ARCHITECT"), false, List.of(), null),
                new StageDef("IN_PROGRESS", List.of("BACKEND", "FRONTEND"), false, List.of(), null),
                new StageDef("IN_REVIEW", List.of("QA"), false, List.of(), null));
        InMemoryMembers members = new InMemoryMembers();
        members.add(new MemberView(OWNER, TestTenant.WORKSPACE, "Diana", false, "OWNER", "#fff", null, ec.kuador.board.member.MemberAccess.OWNER));
        members.add(new MemberView(ARCHITECT, TestTenant.WORKSPACE, "Agente Arquitecto", true, "ARCHITECT", "#fff", null));
        service = new IssueService(new IssueServiceTest.InMemoryIssues(), projects, members, new InMemoryCatalog(), e -> {},
                Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC));
        service.setEvidence(List.of(new CardEvidence() {
            @Override
            public boolean agentWorking(String issueKey) {
                return working.contains(issueKey);
            }
        }));
    }

    private Issue card(IssueType type, String column) {
        return service.create("MED", new IssueDetails("Suscripciones", null, type, null, null, null, null, null), OWNER, column);
    }

    /** Una épica en Diseño con tres tareas en espera: la 2 y la 3 dependen de la 1 (como PLATODO-59 a 67). */
    private List<String> plannedEpic(Issue epic) {
        return service.createEpicTasks(epic.key(), List.of(
                new IssueApi.PlannedTask("Contrato", null, "BACKEND", List.of()),
                new IssueApi.PlannedTask("Alta de complejos", null, "BACKEND", List.of(1)),
                new IssueApi.PlannedTask("Planes", null, "BACKEND", List.of(1))), ARCHITECT).stream().map(IssueApi.IssueRef::key).toList();
    }

    private boolean exists(String key) {
        try {
            service.get(key);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Test
    void tasksThatDependOnEachOtherAreDeletedTogether() {
        Issue epic = card(IssueType.EPIC, "TODO");
        List<String> tasks = plannedEpic(epic);

        IssueService.DeletePlan plan = service.deleteMany(tasks, OWNER);

        assertThat(plan.deleted()).containsExactlyInAnyOrderElementsOf(tasks);
        assertThat(plan.blocked()).isEmpty();
        assertThat(tasks).noneMatch(this::exists);
        assertThat(exists(epic.key())).as("la épica no estaba marcada").isTrue();
    }

    @Test
    void aTaskOthersDependOnStaysIfTheyAreNotMarked() {
        Issue epic = card(IssueType.EPIC, "TODO");
        List<String> tasks = plannedEpic(epic);

        IssueService.DeletePlan plan = service.deleteMany(List.of(tasks.get(0), tasks.get(1)), OWNER);

        assertThat(plan.deleted()).containsExactly(tasks.get(1));
        assertThat(plan.blocked().get(tasks.get(0))).contains(tasks.get(2)).contains("depende");
        assertThat(exists(tasks.get(0))).isTrue();
    }

    @Test
    void anEpicTakesItsWaitingTasksWithIt() {
        Issue epic = card(IssueType.EPIC, "TODO");
        List<String> tasks = plannedEpic(epic);

        IssueService.DeletePlan preview = service.planDelete(List.of(epic.key()));
        assertThat(preview.withEpics()).containsExactlyInAnyOrderElementsOf(tasks);
        assertThat(exists(epic.key())).as("la vista previa no borra").isTrue();

        service.deleteMany(List.of(epic.key()), OWNER);

        assertThat(exists(epic.key())).isFalse();
        assertThat(tasks).noneMatch(this::exists);
    }

    @Test
    void anEpicWithStartedTasksIsNotDeleted() {
        Issue epic = card(IssueType.EPIC, "IN_PROGRESS");
        List<String> tasks = plannedEpic(epic);
        service.reviewPlan(epic.key(), true, null, OWNER);

        IssueService.DeletePlan plan = service.deleteMany(List.of(epic.key()), OWNER);

        assertThat(plan.deleted()).isEmpty();
        assertThat(plan.blocked().get(epic.key())).contains("ya arrancaron");
        assertThat(tasks).allMatch(this::exists);
    }

    @Test
    void aCardAnAgentIsWorkingIsNotDeletedButTheOthersAre() {
        Issue busy = card(IssueType.TASK, "IN_PROGRESS");
        Issue free = card(IssueType.TASK, "BACKLOG");
        working.add(busy.key());

        IssueService.DeletePlan plan = service.deleteMany(List.of(busy.key(), free.key()), OWNER);

        assertThat(plan.deleted()).containsExactly(free.key());
        assertThat(plan.blocked().get(busy.key())).contains("trabajando");
        assertThat(exists(busy.key())).isTrue();
    }

    @Test
    void deletingOneCardExplainsWhyItCannot() {
        Issue busy = card(IssueType.TASK, "IN_PROGRESS");
        working.add(busy.key());

        assertThatThrownBy(() -> service.delete(busy.key(), OWNER)).isInstanceOf(DomainException.class)
                .hasMessageContaining("No se puede borrar").hasMessageContaining("trabajando");
    }
}
