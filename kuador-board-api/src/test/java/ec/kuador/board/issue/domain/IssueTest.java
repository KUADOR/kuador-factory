package ec.kuador.board.issue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.shared.error.DomainException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class IssueTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private static IssueDetails details(String title, IssueType type, String parent, List<String> labels) {
        return new IssueDetails(title, null, type, null, null, null, parent, labels);
    }

    @Test
    void createsKeyFromProjectAndNumberWithDefaults() {
        Issue issue = Issue.create("KUA", 8, details("  Login  ", IssueType.STORY, null, null), null, "BACKLOG", 0, NOW);

        assertThat(issue.key()).isEqualTo("KUA-8");
        assertThat(issue.title()).isEqualTo("Login");
        assertThat(issue.status()).isEqualTo("BACKLOG");
        assertThat(issue.priority()).isEqualTo(Priority.MEDIUM);
        assertThat(issue.labels()).isEmpty();
    }

    @Test
    void normalizesLabels() {
        Issue issue = Issue.create("KUA", 1, details("x", IssueType.TASK, null, List.of("Backend", " backend ", "", "QA")), null, "TODO", 0, NOW);

        assertThat(issue.labels()).containsExactly("backend", "qa");
    }

    @Test
    void rejectsBlankTitle() {
        assertThatThrownBy(() -> Issue.create("KUA", 1, details(" ", IssueType.TASK, null, null), null, "TODO", 0, NOW))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void epicCannotHaveParent() {
        assertThatThrownBy(() -> Issue.create("KUA", 2, details("x", IssueType.EPIC, "KUA-1", null), null, "TODO", 0, NOW))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void movingUpdatesStatusAndTimestamp() {
        Issue issue = Issue.create("KUA", 1, details("x", IssueType.BUG, null, null), null, "TODO", 0, NOW);
        Instant later = NOW.plusSeconds(60);

        issue.moveTo("IN_REVIEW", later);

        assertThat(issue.status()).isEqualTo("IN_REVIEW");
        assertThat(issue.updatedAt()).isEqualTo(later);
    }

    @Test
    void editReportsOnlyChangedFields() {
        Issue issue = Issue.create("KUA", 1, details("Login", IssueType.STORY, null, List.of("a")), null, "TODO", 0, NOW);
        Instant later = NOW.plusSeconds(60);

        List<FieldChange> changes = issue.edit(
                new IssueDetails("Login", "  ", IssueType.STORY, Priority.HIGH, 5, null, null, List.of("a", "b")), later);

        assertThat(changes).containsExactly(
                new FieldChange("priority", "MEDIUM", "HIGH"),
                new FieldChange("storyPoints", null, "5"),
                new FieldChange("labels", "a", "a, b"));
        assertThat(issue.updatedAt()).isEqualTo(later);
    }

    @Test
    void editWithoutChangesKeepsUpdatedAt() {
        Issue issue = Issue.create("KUA", 1, details("Login", IssueType.STORY, null, null), null, "TODO", 0, NOW);

        assertThat(issue.edit(details("Login", IssueType.STORY, null, null), NOW.plusSeconds(60))).isEmpty();
        assertThat(issue.updatedAt()).isEqualTo(NOW);
    }
}
