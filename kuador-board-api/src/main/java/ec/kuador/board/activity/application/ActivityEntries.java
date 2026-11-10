package ec.kuador.board.activity.application;

import ec.kuador.board.activity.domain.ActivityEntry;
import ec.kuador.board.activity.domain.ActivityType;
import ec.kuador.board.issue.IssueAssigned;
import ec.kuador.board.issue.IssueCommented;
import ec.kuador.board.issue.IssueCreated;
import ec.kuador.board.issue.IssueDeleted;
import ec.kuador.board.issue.IssueEvent;
import ec.kuador.board.issue.IssueMoved;
import ec.kuador.board.issue.IssueUpdated;
import java.util.List;
import java.util.UUID;

/** Traduce cada evento de issue a líneas de historial. */
final class ActivityEntries {

    private ActivityEntries() {}

    static List<ActivityEntry> from(IssueEvent event) {
        return switch (event) {
            case IssueCreated e -> List.of(entry(e, ActivityType.CREATED, "status", null, e.status()));
            case IssueUpdated e -> e.changes().stream()
                    .map(c -> entry(e, ActivityType.UPDATED, c.field(), c.from(), c.to()))
                    .toList();
            case IssueMoved e -> List.of(entry(e, ActivityType.MOVED, "status", e.fromStatus(), e.toStatus()));
            case IssueAssigned e -> List.of(entry(e, ActivityType.ASSIGNED, "assignee", text(e.fromAssigneeId()), text(e.toAssigneeId())));
            case IssueCommented e -> List.of(entry(e, ActivityType.COMMENTED, null, null, e.excerpt()));
            case IssueDeleted e -> List.of(entry(e, ActivityType.DELETED, "title", e.title(), null));
        };
    }

    private static ActivityEntry entry(IssueEvent e, ActivityType type, String field, String from, String to) {
        return new ActivityEntry(UUID.randomUUID(), e.eventId(), e.issueKey(), e.projectKey(), e.actorId(), type, field, from, to,
                e.occurredAt());
    }

    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }
}
