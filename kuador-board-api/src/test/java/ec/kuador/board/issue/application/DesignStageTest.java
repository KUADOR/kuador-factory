package ec.kuador.board.issue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.catalog.Requirement;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.issue.CardEvidence;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Diseño de una fábrica: las épicas pasan primero por UI/UX (sus pantallas) y después por el Arquitecto, que no las
 * entrega sin el diagrama de la solución. Las demás tarjetas van directo al Arquitecto y no necesitan diagrama.
 */
@ExtendWith(TestTenant.class)
class DesignStageTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID UX = UUID.randomUUID();
    private static final UUID ARCHITECT = UUID.randomUUID();

    private IssueService service;
    private InMemoryMembers members;
    /** Documentos escritos, como «clave:rol», y tarjetas con diagrama. */
    private final Set<String> written = new HashSet<>();
    private final Set<String> diagrams = new HashSet<>();

    @BeforeEach
    void setUp() {
        InMemoryProjects projects = new InMemoryProjects();
        projects.add("MED", "SOFTWARE", null); // BACKLOG → TODO → IN_PROGRESS → IN_REVIEW → DONE
        projects.stages("MED", 3,
                new StageDef("TODO", List.of("ARCHITECT"), false, List.of(), null, null, false, null,
                        List.of(new Requirement(Requirement.Kind.DIAGRAM, null)), null, List.of("UX_UI")), // Diseño
                new StageDef("IN_PROGRESS", List.of("BACKEND"), false, List.of(), null));
        members = new InMemoryMembers();
        members.add(new MemberView(OWNER, TestTenant.WORKSPACE, "Diana", false, "OWNER", "#fff", null, ec.kuador.board.member.MemberAccess.OWNER));
        members.add(new MemberView(UX, TestTenant.WORKSPACE, "Agente UI/UX", true, "UX_UI", "#fff", null));
        members.add(new MemberView(ARCHITECT, TestTenant.WORKSPACE, "Agente Arquitecto", true, "ARCHITECT", "#fff", null));
        service = new IssueService(new IssueServiceTest.InMemoryIssues(), projects, members, new InMemoryCatalog(), e -> {},
                Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC));
        service.setEvidence(List.of(new CardEvidence() {
            @Override
            public boolean hasDiagram(String issueKey) {
                return diagrams.contains(issueKey);
            }

            @Override
            public Optional<Boolean> hasDocumentOf(String projectKey, String issueKey, String role) {
                return Optional.of(written.contains(issueKey + ":" + role));
            }
        }));
    }

    private Issue card(IssueType type) {
        return service.create("MED", new IssueDetails("Agenda de citas", null, type, null, null, null, null, null), OWNER, "TODO");
    }

    private List<String> roles(String key) {
        return service.openForAgents("MED").stream().filter(q -> q.key().equals(key)).findFirst().orElseThrow().agentRoles();
    }

    @Test
    void anEpicGoesFirstToUiUxAndThenToTheArchitectInTheSameColumn() {
        Issue epic = card(IssueType.EPIC);
        assertThat(roles(epic.key())).as("primero las pantallas").containsExactly("UX_UI");

        service.take(epic.key(), UX);
        assertThat(service.notReadyToDeliver(epic.key(), UX)).hasValueSatisfying(m -> assertThat(m).contains("write_document"));
        assertThatThrownBy(() -> service.deliver(epic.key(), "Listo", UX)).isInstanceOf(DomainException.class).hasMessageContaining("documento");

        written.add(epic.key() + ":UX_UI");
        Issue after = service.deliver(epic.key(), "Pantallas listas", UX);

        assertThat(after.status()).as("sigue en Diseño").isEqualTo("TODO");
        assertThat(after.assigneeId()).isNull();
        assertThat(roles(epic.key())).as("ahora el Arquitecto").containsExactly("ARCHITECT");
    }

    @Test
    void theArchitectCannotDeliverAnEpicWithoutTheSolutionDiagram() {
        Issue epic = card(IssueType.EPIC);
        written.add(epic.key() + ":UX_UI");
        service.take(epic.key(), ARCHITECT);

        assertThat(service.notReadyToDeliver(epic.key(), ARCHITECT)).hasValueSatisfying(m -> assertThat(m).contains("```mermaid"));
        assertThatThrownBy(() -> service.deliver(epic.key(), "Plan listo", ARCHITECT))
                .isInstanceOf(DomainException.class).hasMessageContaining("diagrama de la solución");

        diagrams.add(epic.key());
        assertThat(service.notReadyToDeliver(epic.key(), ARCHITECT)).isEmpty();
        assertThat(service.deliver(epic.key(), "Plan listo", ARCHITECT).status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void aDiagramInTheDeliveryCommentAlsoCounts() {
        Issue epic = card(IssueType.EPIC);
        written.add(epic.key() + ":UX_UI");
        service.take(epic.key(), ARCHITECT);

        Issue moved = service.deliver(epic.key(), "Plan listo\n\n```mermaid\nflowchart LR\n  A --> B\n```", ARCHITECT);

        assertThat(moved.status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void aStoryGoesStraightToTheArchitectAndNeedsNoDiagram() {
        Issue story = card(IssueType.STORY);
        assertThat(roles(story.key())).containsExactly("ARCHITECT");

        service.take(story.key(), ARCHITECT);

        assertThat(service.deliver(story.key(), "Plan listo", ARCHITECT).status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void withoutAUiUxAgentTheEpicGoesStraightToTheArchitect() {
        members.all.remove(UX);
        Issue epic = card(IssueType.EPIC);

        assertThat(roles(epic.key())).containsExactly("ARCHITECT");
    }

    @Test
    void uiUxCannotTakeAStoryInDesign() {
        Issue story = card(IssueType.STORY);

        assertThatThrownBy(() -> service.take(story.key(), UX)).hasMessageContaining("la cubren");
    }
}
