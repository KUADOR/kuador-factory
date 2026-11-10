package ec.kuador.board.activity.application;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.activity.domain.ActivityEntry;
import ec.kuador.board.activity.domain.ActivityRepository;
import ec.kuador.board.activity.domain.ActivityType;
import ec.kuador.board.issue.IssueAssigned;
import ec.kuador.board.issue.IssueMoved;
import ec.kuador.board.issue.IssueUpdated;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import ec.kuador.board.testing.NoTx;
import ec.kuador.board.testing.TestTenant;
import org.junit.jupiter.api.Test;

class ActivityEntriesTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();

    @Test
    void oneEntryPerChangedFieldSharingTheEvent() {
        UUID eventId = UUID.randomUUID();
        List<ActivityEntry> entries = ActivityEntries.from(new IssueUpdated(eventId, TestTenant.WORKSPACE, "KUA-1", "KUA", ACTOR, NOW, List.of(
                new IssueUpdated.Change("title", "a", "b"), new IssueUpdated.Change("priority", "LOW", "HIGH"))));

        assertThat(entries).extracting(ActivityEntry::field).containsExactly("title", "priority");
        assertThat(entries).allMatch(e -> e.eventId().equals(eventId) && e.type() == ActivityType.UPDATED && e.actorId().equals(ACTOR));
    }

    @Test
    void movedAndAssignedKeepFromAndTo() {
        UUID agent = UUID.randomUUID();
        ActivityEntry moved = ActivityEntries.from(new IssueMoved(UUID.randomUUID(), TestTenant.WORKSPACE, "KUA-1", "KUA", ACTOR, NOW, "TODO", "IN_REVIEW", "TODO", "IN_PROGRESS")).getFirst();
        ActivityEntry assigned = ActivityEntries.from(new IssueAssigned(UUID.randomUUID(), TestTenant.WORKSPACE, "KUA-1", "KUA", ACTOR, NOW, null, agent)).getFirst();

        assertThat(moved).extracting(ActivityEntry::from, ActivityEntry::to).containsExactly("TODO", "IN_REVIEW");
        assertThat(assigned).extracting(ActivityEntry::from, ActivityEntry::to).containsExactly(null, agent.toString());
    }

    @Test
    void recorderIgnoresAnEventItAlreadyStored() {
        List<ActivityEntry> stored = new ArrayList<>();
        ActivityRepository repo = new ActivityRepository() {
            public boolean existsByEventId(UUID id) { return stored.stream().anyMatch(e -> e.eventId().equals(id)); }
            public void saveAll(List<ActivityEntry> entries) { stored.addAll(entries); }
            public List<ActivityEntry> findByIssue(String key, int limit) { return stored; }
            public List<ActivityEntry> findByProject(String key, int limit) { return stored; }
        };
        ActivityRecorder recorder = new ActivityRecorder(repo, new NoTx());
        IssueMoved event = new IssueMoved(UUID.randomUUID(), TestTenant.WORKSPACE, "KUA-1", "KUA", ACTOR, NOW, "TODO", "DONE", "TODO", "DONE");

        recorder.on(event);
        recorder.on(event); // reentrega del registro de eventos

        assertThat(stored).hasSize(1);
    }
}
