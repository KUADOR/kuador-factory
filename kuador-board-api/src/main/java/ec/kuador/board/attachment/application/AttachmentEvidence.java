package ec.kuador.board.attachment.application;

import ec.kuador.board.attachment.domain.AttachmentStore;
import ec.kuador.board.issue.CardEvidence;
import ec.kuador.board.shared.tenant.TenantContext;
import org.springframework.stereotype.Component;

/** Para las etapas que exigen un archivo: si la tarjeta tiene adjuntos. */
@Component
class AttachmentEvidence implements CardEvidence {

    private final AttachmentStore store;

    AttachmentEvidence(AttachmentStore store) {
        this.store = store;
    }

    @Override
    public boolean hasAttachment(String issueKey) {
        return !store.byIssue(TenantContext.require(), issueKey).isEmpty();
    }
}
