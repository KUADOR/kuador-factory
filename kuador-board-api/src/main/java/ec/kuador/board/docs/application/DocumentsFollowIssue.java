package ec.kuador.board.docs.application;

import ec.kuador.board.docs.domain.DocumentRepository;
import ec.kuador.board.issue.IssueUpdated;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Una tarjeta que pasa a trabajarse en otro proyecto se lleva sus documentos, en la misma transacción. */
@Component
class DocumentsFollowIssue {

    private final DocumentRepository documents;

    DocumentsFollowIssue(DocumentRepository documents) {
        this.documents = documents;
    }

    @EventListener
    void on(IssueUpdated event) {
        event.changes().stream().filter(c -> "subject".equals(c.field()) && c.to() != null).findFirst()
                .ifPresent(c -> documents.moveIssue(event.issueKey(), c.to()));
    }
}
