package ec.kuador.board.issue.application;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.issue.domain.IssueDetails;
import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.testing.InMemoryCatalog;
import ec.kuador.board.testing.InMemoryMembers;
import ec.kuador.board.testing.InMemoryProjects;
import ec.kuador.board.testing.TestTenant;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Trabajo en paralelo: el reparto puede arrancar solo al llegar a la etapa de trabajo, y una tarea queda libre cuando su
 * dependencia pasa su revisión (llega a la última puerta), no cuando termina del todo.
 */
@ExtendWith(TestTenant.class)
class ParallelTasksTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID DEV = UUID.randomUUID();
    private static final UUID PO = UUID.randomUUID();

    private IssueService service(boolean autoPlan) {
        InMemoryProjects projects = new InMemoryProjects();
        projects.add("KUA", "SOFTWARE", null);
        projects.stages("KUA", 3,
                new StageDef("IN_PROGRESS", List.of("BACKEND", "FRONTEND"), false, List.of(), null, null, autoPlan),
                new StageDef("IN_REVIEW", List.of(), true, List.of("OWNER"), "IN_PROGRESS"));
        InMemoryMembers members = new InMemoryMembers();
        members.add(new MemberView(OWNER, TestTenant.WORKSPACE, "Diana", false, "OWNER", "#fff", null, ec.kuador.board.member.MemberAccess.OWNER));
        members.add(new MemberView(DEV, TestTenant.WORKSPACE, "Dani", false, "DEV", "#fff", null));
        members.add(new MemberView(PO, TestTenant.WORKSPACE, "Agente PO", true, "PRODUCT_OWNER", "#fff", null));
        return new IssueService(new IssueServiceTest.InMemoryIssues(), projects, members, new InMemoryCatalog(), e -> {},
                Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC));
    }

    /** Épica «Pacientes» repartida con el contrato primero: backend y frontend dependen solo del contrato. */
    private List<String> planPatients(IssueService service, Issue epic) {
        return service.createEpicTasks(epic.key(), List.of(
                new IssueApi.PlannedTask("Contrato API: Pacientes", "contracts/pacientes.openapi.yaml", "BACKEND", List.of()),
                new IssueApi.PlannedTask("Backend: Pacientes", null, "BACKEND", List.of(1)),
                new IssueApi.PlannedTask("Frontend: Pacientes (contra el mock)", null, "FRONTEND", List.of(1))), OWNER)
                .stream().map(IssueApi.IssueRef::key).toList();
    }

    private Issue epic(IssueService service) {
        return service.create("KUA", new IssueDetails("Pacientes", null, IssueType.EPIC, null, null, null, null, null), OWNER, "TODO");
    }

    @Test
    void withAutoPlanTheSplitStartsWhenTheEpicReachesTheWorkStage() {
        IssueService service = service(true);
        Issue epic = epic(service);
        List<String> tasks = planPatients(service, epic);

        service.move(epic.key(), "IN_PROGRESS", 0, OWNER);

        assertThat(tasks).allSatisfy(k -> assertThat(service.get(k).status()).as("arrancó sin aprobar el reparto").isEqualTo("IN_PROGRESS"));
        assertThat(service.comments(epic.key())).anyMatch(c -> c.body().startsWith("**Reparto en marcha:**"));
    }

    @Test
    void withoutAutoPlanTheSplitWaitsForAPerson() {
        IssueService service = service(false);
        Issue epic = epic(service);
        List<String> tasks = planPatients(service, epic);

        service.move(epic.key(), "IN_PROGRESS", 0, OWNER);

        assertThat(service.get(tasks.getFirst()).status()).as("espera la aprobación del reparto").isEqualTo("BACKLOG");
        service.reviewPlan(epic.key(), true, null, OWNER);
        assertThat(service.get(tasks.getFirst()).status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void aDependencyFreesTheOthersWhenItPassesReviewNotWhenItIsDone() {
        IssueService service = service(true);
        Issue epic = epic(service);
        List<String> tasks = planPatients(service, epic);
        service.move(epic.key(), "IN_PROGRESS", 0, OWNER);
        String contract = tasks.get(0);
        String backend = tasks.get(1);
        String frontend = tasks.get(2);

        assertThat(service.taskInfo(backend).orElseThrow().pendingDependencies()).containsExactly(contract);
        assertThat(service.taskInfo(frontend).orElseThrow().pendingDependencies()).containsExactly(contract);

        service.move(contract, "IN_REVIEW", 0, OWNER); // el contrato pasó QA: está en la última puerta

        assertThat(service.taskInfo(backend).orElseThrow().pendingDependencies()).as("backend y frontend arrancan a la vez").isEmpty();
        assertThat(service.taskInfo(frontend).orElseThrow().pendingDependencies()).isEmpty();
    }

    @Test
    void myDecisionsListGatesSplitsAndCardsAnAgentLeftMe() {
        IssueService service = service(false);
        Issue epic = epic(service);
        planPatients(service, epic);
        service.move(epic.key(), "IN_PROGRESS", 0, OWNER); // reparto por aprobar
        Issue pr = service.create("KUA", new IssueDetails("Corrige IVA", null, IssueType.STORY, null, null, null, null, null), OWNER, "IN_PROGRESS");
        service.move(pr.key(), "IN_REVIEW", 0, OWNER); // en la puerta que aprueba OWNER
        Issue asked = service.create("KUA", new IssueDetails("Historia clínica", null, IssueType.STORY, null, null, null, null, null), OWNER, "TODO");
        service.assign(asked.key(), OWNER, PO);
        service.addComment(asked.key(), PO, "**Necesito una decisión:** ¿guardamos alergias como texto o catálogo?");

        List<IssueService.Decision> mine = service.decisionsFor(OWNER);
        assertThat(mine).extracting(IssueService.Decision::kind, IssueService.Decision::issueKey)
                .contains(org.assertj.core.groups.Tuple.tuple("GATE", pr.key()), org.assertj.core.groups.Tuple.tuple("PLAN", epic.key()),
                        org.assertj.core.groups.Tuple.tuple("HANDED", asked.key()));
        assertThat(mine.stream().filter(d -> d.kind().equals("HANDED")).findFirst().orElseThrow().detail()).contains("alergias");
        assertThat(service.decisionsFor(DEV)).as("no aprueba esa puerta ni supervisa nada").isEmpty();
        assertThat(service.decisionsFor(PO)).as("un agente no tiene decisiones").isEmpty();
    }
}
