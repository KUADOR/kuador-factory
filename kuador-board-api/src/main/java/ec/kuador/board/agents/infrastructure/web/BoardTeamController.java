package ec.kuador.board.agents.infrastructure.web;

import ec.kuador.board.agents.application.BoardTeam;
import ec.kuador.board.agents.application.WorkspaceAi;
import ec.kuador.board.agents.domain.AgentMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Equipo de agentes de cada tablero, dónde trabaja cada agente y la clave de IA del espacio. */
@RestController
class BoardTeamController {

    private final BoardTeam team;
    private final WorkspaceAi ai;

    BoardTeamController(BoardTeam team, WorkspaceAi ai) {
        this.team = team;
        this.ai = ai;
    }

    record AddRequest(@NotNull UUID memberId) {}

    record CreateRequest(@Size(max = 120) String name, @NotBlank @Size(max = 40) String role, @Size(max = 4000) String description, AgentMode mode,
            @Size(max = 60) String model) {}

    record ProfileRequest(@NotNull AgentMode mode, @Size(max = 60) String model) {}

    record AiRequest(@Size(max = 300) String apiKey, @Size(max = 60) String defaultModel) {}

    @GetMapping("/api/projects/{key}/team")
    List<BoardTeam.TeamAgent> list(@PathVariable String key) {
        return team.team(key.toUpperCase());
    }

    @PostMapping("/api/projects/{key}/team")
    List<BoardTeam.TeamAgent> add(@PathVariable String key, @Valid @RequestBody AddRequest r) {
        return team.add(key.toUpperCase(), r.memberId());
    }

    @PostMapping("/api/projects/{key}/team/agents")
    List<BoardTeam.TeamAgent> create(@PathVariable String key, @Valid @RequestBody CreateRequest r) {
        return team.create(key.toUpperCase(), r.name(), r.role(), r.description(), r.mode(), r.model());
    }

    @DeleteMapping("/api/projects/{key}/team/{memberId}")
    List<BoardTeam.TeamAgent> remove(@PathVariable String key, @PathVariable UUID memberId) {
        return team.remove(key.toUpperCase(), memberId);
    }

    @PutMapping("/api/agents/{memberId}/profile")
    BoardTeam.TeamAgent profile(@PathVariable UUID memberId, @Valid @RequestBody ProfileRequest r) {
        return team.setProfile(memberId, r.mode(), r.model());
    }

    @GetMapping("/api/workspace/ai")
    WorkspaceAi.Status ai() {
        return ai.status();
    }

    @PutMapping("/api/workspace/ai")
    WorkspaceAi.Status saveAi(@Valid @RequestBody AiRequest r) {
        return ai.save(r.apiKey(), r.defaultModel());
    }

    @DeleteMapping("/api/workspace/ai")
    WorkspaceAi.Status removeAi() {
        return ai.remove();
    }
}
