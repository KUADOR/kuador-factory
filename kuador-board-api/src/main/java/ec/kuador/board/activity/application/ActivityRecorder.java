package ec.kuador.board.activity.application;

import ec.kuador.board.activity.domain.ActivityEntry;
import ec.kuador.board.activity.domain.ActivityRepository;
import ec.kuador.board.activity.domain.ActivityType;
import ec.kuador.board.docs.DocumentSaved;
import ec.kuador.board.issue.IssueEvent;
import ec.kuador.board.shared.tenant.TenantAwareListener;
import java.util.List;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Escucha los eventos después del commit, en otro hilo y en el espacio del evento. Si falla, Spring
 * Modulith deja el evento pendiente y lo reintenta; por eso ignora eventos ya registrados.
 */
@Component
class ActivityRecorder extends TenantAwareListener {

    private final ActivityRepository activity;

    ActivityRecorder(ActivityRepository activity, PlatformTransactionManager txManager) {
        super(txManager);
        this.activity = activity;
    }

    @Async
    @TransactionalEventListener
    void on(IssueEvent event) {
        inTenantTransaction(event, () -> record(event.eventId(), ActivityEntries.from(event)));
    }

    /** Se borró un tablero: su actividad también. */
    @Async
    @TransactionalEventListener
    void on(ec.kuador.board.project.ProjectDeleted event) {
        inTenantTransaction(event, () -> activity.deleteByProject(event.workspaceId(), event.projectKey()));
    }

    @Async
    @TransactionalEventListener
    void on(DocumentSaved event) {
        inTenantTransaction(event, () -> record(event.eventId(), List.of(new ActivityEntry(UUID.randomUUID(), event.eventId(),
                event.issueKey(), event.projectKey(), event.actorId(), ActivityType.DOCUMENT, event.kind(), event.documentId().toString(),
                String.valueOf(event.version()), event.occurredAt()))));
    }

    private void record(UUID eventId, List<ActivityEntry> entries) {
        if (!activity.existsByEventId(eventId)) {
            activity.saveAll(entries);
        }
    }
}
