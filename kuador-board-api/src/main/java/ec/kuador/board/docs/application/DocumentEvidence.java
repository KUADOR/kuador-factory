package ec.kuador.board.docs.application;

import ec.kuador.board.docs.domain.DocumentRepository;
import ec.kuador.board.issue.CardEvidence;
import org.springframework.stereotype.Component;

/** Para las etapas que exigen un documento: si la tarjeta tiene uno de ese tipo. */
@Component
class DocumentEvidence implements CardEvidence {

    private final DocumentRepository documents;

    DocumentEvidence(DocumentRepository documents) {
        this.documents = documents;
    }

    @Override
    public boolean hasDocument(String issueKey, String kind) {
        return documents.findByIssue(issueKey).stream().anyMatch(d -> d.kind().equalsIgnoreCase(kind));
    }
}
