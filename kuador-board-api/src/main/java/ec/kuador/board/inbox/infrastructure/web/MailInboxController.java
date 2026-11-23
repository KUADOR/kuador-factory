package ec.kuador.board.inbox.infrastructure.web;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.connections.ConnectionsApi;
import ec.kuador.board.inbox.application.MailInboxService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Bandeja de correo de un tablero: la configura quien administra (usa los buzones de la empresa). */
@RestController
class MailInboxController {

    private final MailInboxService inboxes;

    MailInboxController(MailInboxService inboxes) {
        this.inboxes = inboxes;
    }

    @GetMapping("/api/projects/{key}/inboxes")
    List<MailInboxService.View> list(@PathVariable String key) {
        return inboxes.list(key);
    }

    /** Los buzones conectados que pueden alimentar una bandeja. */
    @GetMapping("/api/inboxes/mailboxes")
    List<ConnectionsApi.Mailbox> mailboxes() {
        return inboxes.mailboxes();
    }

    @PostMapping("/api/projects/{key}/inboxes")
    @ResponseStatus(HttpStatus.CREATED)
    MailInboxService.View create(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable String key, @RequestBody MailInboxService.Input in) {
        return inboxes.create(who, key, in);
    }

    @PutMapping("/api/inboxes/{id}")
    MailInboxService.View update(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable UUID id, @RequestBody MailInboxService.Input in) {
        return inboxes.update(who, id, in);
    }

    @DeleteMapping("/api/inboxes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable UUID id) {
        inboxes.delete(who, id);
    }

    @PostMapping("/api/inboxes/{id}/check")
    MailInboxService.Check check(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable UUID id) {
        return inboxes.checkNow(who, id);
    }
}
