package ec.kuador.board.live.application;

import ec.kuador.board.docs.DocumentSaved;
import ec.kuador.board.issue.IssueEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pasa los eventos a las conexiones abiertas después del commit (así quien recarga ya ve el cambio).
 * En otro hilo: un navegador lento no frena la petición que hizo el cambio. Si se pierde un aviso no
 * pasa nada grave: la próxima recarga trae el estado real.
 */
@Component
class LiveRelay {

    private final LiveHub hub;

    LiveRelay(LiveHub hub) {
        this.hub = hub;
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(IssueEvent event) {
        hub.publish(event.workspaceId(), new LiveHub.Change(event.projectKey(), event.issueKey(), type(event), event.actorId()));
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(DocumentSaved event) {
        hub.publish(event.workspaceId(), new LiveHub.Change(event.projectKey(), event.issueKey(), "DOCUMENTO", event.actorId()));
    }

    private static String type(IssueEvent event) {
        return switch (event) {
            case ec.kuador.board.issue.IssueCreated e -> "CREADO";
            case ec.kuador.board.issue.IssueUpdated e -> "EDITADO";
            case ec.kuador.board.issue.IssueMoved e -> "MOVIDO";
            case ec.kuador.board.issue.IssueAssigned e -> "ASIGNADO";
            case ec.kuador.board.issue.IssueCommented e -> "COMENTADO";
            case ec.kuador.board.issue.IssueDeleted e -> "BORRADO";
        };
    }
}
