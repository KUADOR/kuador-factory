package ec.kuador.board.issue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.issue.IssueAssigned;
import ec.kuador.board.issue.IssueCreated;
import ec.kuador.board.issue.IssueEvent;
import ec.kuador.board.issue.IssueMoved;
import ec.kuador.board.issue.IssueUpdated;
import ec.kuador.board.issue.domain.Comment;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.issue.domain.IssueDetails;
import ec.kuador.board.issue.domain.IssueFilter;
import ec.kuador.board.issue.domain.IssueRepository;
import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.Transitions;
import ec.kuador.board.testing.InMemoryCatalog;
import ec.kuador.board.testing.InMemoryMembers;
import ec.kuador.board.testing.InMemoryProjects;
import ec.kuador.board.testing.TestTenant;
import ec.kuador.board.shared.error.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestTenant.class)
class IssueServiceTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();

    private InMemoryIssues issues;
    private InMemoryProjects projects;
    private IssueService service;
    private final List<Object> published = new ArrayList<>();

    @BeforeEach
    void setUp() {
        issues = new InMemoryIssues();
        projects = new InMemoryProjects();
        projects.add("KUA", "SOFTWARE", null);
        InMemoryMembers members = new InMemoryMembers();
        members.add(new MemberView(ACTOR, TestTenant.WORKSPACE, "Diana", false, "OWNER", "#fff", null));
        members.add(new MemberView(AGENT, TestTenant.WORKSPACE, "Agente QA", true, "QA", "#fff", null));
        published.clear();
        service = new IssueService(issues, projects, members, new InMemoryCatalog(), published::add, Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC));
    }

    private Issue create(String title, String status) {
        return service.create("KUA", new IssueDetails(title, null, IssueType.TASK, null, null, null, null, null), ACTOR, status);
    }

    private List<String> column(String status) {
        return issues.findColumn("KUA", status).stream().map(Issue::title).toList();
    }

    @Test
    void newIssuesGoToTheBottomOfTheirColumn() {
        create("a", "TODO");
        create("b", "TODO");

        assertThat(column("TODO")).containsExactly("a", "b");
    }

    @Test
    void moveInsertsAtIndexAndReordersTargetColumn() {
        create("a", "TODO");
        create("b", "TODO");
        Issue c = create("c", "BACKLOG");

        service.move(c.key(), "TODO", 1, ACTOR);

        assertThat(column("TODO")).containsExactly("a", "c", "b");
        assertThat(column("BACKLOG")).isEmpty();
    }

    @Test
    void moveWithinSameColumnAndClampsIndex() {
        Issue a = create("a", "TODO");
        create("b", "TODO");
        create("c", "TODO");

        service.move(a.key(), "TODO", 99, ACTOR);

        assertThat(column("TODO")).containsExactly("b", "c", "a");
    }

    @Test
    void rejectsUnknownAssignee() {
        IssueDetails d = new IssueDetails("x", null, IssueType.TASK, null, null, UUID.randomUUID(), null, null);

        assertThatThrownBy(() -> service.create("KUA", d, null, null)).isInstanceOf(DomainException.class);
    }

    @Test
    void onlyEpicsCanBeParents() {
        Issue task = create("task", "TODO");
        IssueDetails child = new IssueDetails("child", null, IssueType.TASK, null, null, null, task.key(), null);

        assertThatThrownBy(() -> service.create("KUA", child, null, null)).isInstanceOf(DomainException.class);
    }

    private <T extends IssueEvent> List<T> events(Class<T> type) {
        return published.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void createPublishesCreatedAndAssignedWhenItHasAnAssignee() {
        IssueDetails d = new IssueDetails("x", null, IssueType.STORY, null, null, AGENT, null, null);
        Issue issue = service.create("KUA", d, ACTOR, "TODO");

        IssueCreated created = events(IssueCreated.class).getFirst();
        assertThat(created.issueKey()).isEqualTo(issue.key());
        assertThat(created.actorId()).isEqualTo(ACTOR);
        assertThat(created.status()).isEqualTo("TODO");
        IssueAssigned assigned = events(IssueAssigned.class).getFirst();
        assertThat(assigned.fromAssigneeId()).isNull();
        assertThat(assigned.toAssigneeId()).isEqualTo(AGENT);
    }

    @Test
    void restrictedFlowRejectsMovesItDoesNotAllowAndSaysWhereItCanGo() {
        projects.restrict("KUA", new Transitions.Transition("BACKLOG", "TODO"), new Transitions.Transition("TODO", "IN_PROGRESS"),
                new Transitions.Transition("IN_PROGRESS", "IN_REVIEW"), new Transitions.Transition("IN_REVIEW", "DONE"));
        Issue issue = create("a", "BACKLOG");

        assertThatThrownBy(() -> service.move(issue.key(), "DONE", 0, ACTOR)).isInstanceOf(DomainException.class)
                .hasMessageContaining("no permite pasar de «Backlog» a «Hecho»").hasMessageContaining("Por hacer");
        service.move(issue.key(), "TODO", 0, ACTOR);
        assertThat(column("TODO")).containsExactly("a");
        service.move(issue.key(), "TODO", 0, ACTOR); // reordenar en la misma columna siempre vale
    }

    @Test
    void movePublishesOnlyWhenTheColumnChanges() {
        Issue a = create("a", "TODO");
        create("b", "TODO");
        published.clear();

        service.move(a.key(), "TODO", 1, ACTOR);
        assertThat(published).isEmpty();

        service.move(a.key(), "IN_REVIEW", 0, AGENT);
        IssueMoved moved = events(IssueMoved.class).getFirst();
        assertThat(moved.fromStatus()).isEqualTo("TODO");
        assertThat(moved.toStatus()).isEqualTo("IN_REVIEW");
        assertThat(moved.fromCategory()).isEqualTo("TODO");
        assertThat(moved.toCategory()).isEqualTo("IN_PROGRESS");
        assertThat(moved.actorId()).isEqualTo(AGENT);
    }

    @Test
    void editPublishesChangedFieldsAndAssignmentSeparately() {
        Issue a = create("a", "TODO");
        published.clear();

        service.edit(a.key(), new IssueDetails("a", null, IssueType.TASK, null, null, null, null, null), ACTOR);
        assertThat(published).as("guardar sin cambios no emite eventos").isEmpty();

        service.edit(a.key(), new IssueDetails("Login", "nuevo", IssueType.BUG, null, 3, AGENT, null, List.of("qa")), ACTOR);
        assertThat(events(IssueUpdated.class).getFirst().changes())
                .extracting(IssueUpdated.Change::field)
                .containsExactly("title", "description", "type", "storyPoints", "labels");
        assertThat(events(IssueAssigned.class).getFirst().toAssigneeId()).isEqualTo(AGENT);
    }

    @Test
    void assigningTheSameMemberAgainPublishesNothing() {
        Issue a = create("a", "TODO");
        service.assign(a.key(), AGENT, ACTOR);
        published.clear();

        service.assign(a.key(), AGENT, ACTOR);

        assertThat(published).isEmpty();
    }

    @Test
    void withoutStatusTheIssueGoesToTheFirstColumn() {
        Issue issue = create("a", null);

        assertThat(issue.status()).isEqualTo("BACKLOG");
    }

    @Test
    void rejectsColumnsOutsideTheProjectWorkflow() {
        Issue a = create("a", "TODO");

        assertThatThrownBy(() -> create("b", "WON")).isInstanceOf(DomainException.class).hasMessageContaining("WON");
        assertThatThrownBy(() -> service.move(a.key(), "WON", 0, ACTOR)).isInstanceOf(DomainException.class);
    }

    @Test
    void nonSoftwareProjectsOnlyAcceptTasksAndEpics() {
        projects.add("KUA", "SALES", null);
        IssueDetails bug = new IssueDetails("x", null, IssueType.BUG, null, null, null, null, null);

        assertThatThrownBy(() -> service.create("KUA", bug, ACTOR, null)).isInstanceOf(DomainException.class);
        assertThat(create("Oportunidad Clínica Andes", null).type()).isEqualTo(IssueType.TASK);
    }

    @Test
    void boardFollowsTheWorkflowOrder() {
        create("hecho", "DONE");
        create("backlog", "BACKLOG");
        create("qa", "IN_REVIEW");

        assertThat(service.board("KUA", null)).extracting(Issue::title).containsExactly("backlog", "qa", "hecho");
    }

    /** Adaptador en memoria: prueba los casos de uso sin base de datos. */
    static class InMemoryIssues implements IssueRepository {

        private final Map<UUID, Issue> store = new LinkedHashMap<>();

        public Optional<Issue> findByKey(String key) {
            return store.values().stream().filter(i -> i.key().equals(key)).findFirst();
        }
        public boolean existsByKey(String key) { return findByKey(key).isPresent(); }
        public List<Issue> findByProject(String projectKey, IssueFilter filter) { return new ArrayList<>(store.values()); }
        public List<Issue> findColumn(String projectKey, String status) {
            return store.values().stream().filter(i -> i.status().equals(status))
                    .sorted(Comparator.comparingInt(Issue::position)).toList();
        }
        public long countInColumn(String projectKey, String status) { return findColumn(projectKey, status).size(); }
        public int nextPosition(String projectKey, String status) {
            return findColumn(projectKey, status).stream().mapToInt(Issue::position).max().orElse(-1) + 1;
        }
        public Issue save(Issue issue) { store.put(issue.id(), issue); return issue; }
        public void saveAll(List<Issue> list) { list.forEach(this::save); }
        public void delete(Issue issue) { store.remove(issue.id()); }
        private final List<Comment> comments = new ArrayList<>();
        public List<Comment> findComments(Issue issue) { return comments.stream().filter(c -> c.issueId().equals(issue.id())).toList(); }
        public Comment saveComment(Comment comment) { comments.add(comment); return comment; }
    }
}
