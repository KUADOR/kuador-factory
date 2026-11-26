package ec.kuador.board.project.infrastructure.web;

import ec.kuador.board.project.application.TeamService;
import ec.kuador.board.project.application.TeamService.TeamRef;
import ec.kuador.board.project.application.TeamService.TeamView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

/** Los equipos del espacio (Organización → Equipos). Quién puede cambiarlos lo fija la seguridad: quien administra. */
@RestController
class TeamController {

    private final TeamService service;

    TeamController(TeamService service) {
        this.service = service;
    }

    @GetMapping("/api/teams")
    List<TeamView> list(@AuthenticationPrincipal(expression = "memberId()") UUID actorId) {
        return service.list(actorId);
    }

    @GetMapping("/api/teams/{id}")
    TeamView get(@PathVariable UUID id, @AuthenticationPrincipal(expression = "memberId()") UUID actorId) {
        return service.get(id, actorId);
    }

    @PostMapping("/api/teams")
    @ResponseStatus(HttpStatus.CREATED)
    TeamView create(@Valid @RequestBody CreateRequest r) {
        return service.create(r.name(), r.members());
    }

    @PutMapping("/api/teams/{id}")
    TeamView rename(@PathVariable UUID id, @Valid @RequestBody RenameRequest r) {
        return service.rename(id, r.name());
    }

    @DeleteMapping("/api/teams/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    /** Suma una persona o un agente del espacio al equipo. */
    @PostMapping("/api/teams/{id}/members")
    TeamView addMember(@PathVariable UUID id, @Valid @RequestBody MemberRequest r) {
        return service.addMember(id, r.memberId());
    }

    @DeleteMapping("/api/teams/{id}/members/{memberId}")
    TeamView removeMember(@PathVariable UUID id, @PathVariable UUID memberId) {
        return service.removeMember(id, memberId);
    }

    /** El equipo que atiende un tablero. */
    @PutMapping("/api/teams/{id}/boards/{projectKey}")
    TeamRef assign(@PathVariable UUID id, @PathVariable String projectKey) {
        return service.assign(projectKey, id);
    }

    record CreateRequest(@NotBlank @Size(max = 140) String name, @Size(max = 200) List<UUID> members) {}

    record RenameRequest(@NotBlank @Size(max = 140) String name) {}

    record MemberRequest(@NotNull UUID memberId) {}
}
