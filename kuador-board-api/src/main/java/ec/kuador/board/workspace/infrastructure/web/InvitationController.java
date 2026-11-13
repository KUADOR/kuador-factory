package ec.kuador.board.workspace.infrastructure.web;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.member.MemberAccess;
import ec.kuador.board.workspace.application.InvitationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Invitaciones por correo: las personas del equipo invitan; quien recibe el enlace acepta sin sesión. */
@RestController
class InvitationController {

    private final InvitationService invitations;

    InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    /** {@code memberId}: invitar a alguien que ya está en el equipo; si no, se crea con nombre, rol y acceso. */
    /** {@code projects}: los tableros donde queda (quien edita o solo mira ve únicamente esos). */
    record InviteRequest(UUID memberId, @Size(max = 120) String name, @Size(max = 60) String role, MemberAccess access,
            @NotBlank @Size(max = 254) String email, @Size(max = 100) List<@Size(max = 10) String> projects) {}

    record AcceptRequest(@NotBlank @Size(max = 100) String token, @NotBlank @Size(max = 128) String password) {}

    record AcceptResponse(String email) {}

    @GetMapping("/api/invitations")
    List<InvitationService.Pending> pending() {
        return invitations.pending();
    }

    @PostMapping("/api/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    InvitationService.Pending invite(@AuthenticationPrincipal AuthenticatedMember who, @Valid @RequestBody InviteRequest r) {
        return invitations.invite(r.memberId(), r.name(), r.role(), r.access(), r.email(), who.memberId(), r.projects());
    }

    @PostMapping("/api/invitations/{id}/resend")
    InvitationService.Pending resend(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable UUID id) {
        return invitations.resend(id, who.memberId());
    }

    @DeleteMapping("/api/invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable UUID id) {
        invitations.cancel(id);
    }

    @GetMapping("/api/public/invitations")
    InvitationService.Preview preview(@RequestParam @Size(max = 100) String token) {
        return invitations.preview(token);
    }

    @PostMapping("/api/public/invitations/accept")
    AcceptResponse accept(@Valid @RequestBody AcceptRequest r) {
        return new AcceptResponse(invitations.accept(r.token(), r.password()));
    }
}
