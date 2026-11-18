package ec.kuador.board.mcp.infrastructure.web;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.mcp.application.RunnerHub;
import ec.kuador.board.mcp.application.RunnerService;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * El runner (con la conexión de agentes locales de su persona): instalación, señal de vida, avisos, pedir tarjetas y
 * sus préstamos. Y las personas, desde el tablero: sus máquinas, pausarlas, cambiar sus roles o quitarlas.
 */
@RestController
class RunnerController {

    private final RunnerService runners;
    private final RunnerHub hub;

    RunnerController(RunnerService runners, RunnerHub hub) {
        this.runners = runners;
        this.hub = hub;
    }

    // ---- Runner ----

    @GetMapping("/api/runner/setup")
    RunnerService.Setup setup(@AuthenticationPrincipal AuthenticatedMember who) {
        return runners.setup(who);
    }

    @PutMapping("/api/runner/heartbeat")
    RunnerService.MachineView heartbeat(@AuthenticationPrincipal AuthenticatedMember who, @RequestBody RunnerService.Register r) {
        return runners.heartbeat(who, r);
    }

    @GetMapping(value = "/api/runner/events", produces = "text/event-stream")
    SseEmitter events(@AuthenticationPrincipal AuthenticatedMember who) {
        if (!who.runner()) throw new ec.kuador.board.shared.error.ForbiddenException("Solo un runner conectado usa esta ruta.");
        return hub.subscribe(who.workspaceId());
    }

    /** 200 con la tarjeta ya tomada; 204 si no hay nada para esta máquina. */
    @PostMapping("/api/runner/claim")
    ResponseEntity<RunnerService.Claim> claim(@AuthenticationPrincipal AuthenticatedMember who, @RequestBody MachineRequest r) {
        return runners.claim(who, r.machineId()).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 409 si el préstamo ya no es de esta máquina: el runner corta el cliente de IA. */
    @PostMapping("/api/runner/leases/{issueKey}/renew")
    ResponseEntity<Map<String, Boolean>> renew(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable String issueKey,
            @RequestBody MachineRequest r) {
        return runners.renew(who, r.machineId(), issueKey) ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("ok", false));
    }

    @PostMapping("/api/runner/leases/{issueKey}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void release(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable String issueKey, @RequestBody ReleaseRequest r) {
        runners.release(who, r.machineId(), issueKey, r.outcome(), r.message());
    }

    /** El siguiente trabajo de IA de su persona (p. ej. redactar documentos «en mi computador»); 204 si no hay. */
    @PostMapping("/api/runner/jobs/claim")
    ResponseEntity<ec.kuador.board.agents.AiJobsApi.JobTask> claimJob(@AuthenticationPrincipal AuthenticatedMember who, @RequestBody MachineRequest r) {
        return runners.claimJob(who, r.machineId()).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/api/runner/jobs/{id}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void completeJob(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable UUID id, @RequestBody JobResult r) {
        runners.completeJob(who, r.machineId(), id, r.text(), r.error());
    }

    record JobResult(@NotNull UUID machineId, String text, String error) {}

    record MachineRequest(@NotNull UUID machineId) {}

    record ReleaseRequest(@NotNull UUID machineId, String outcome, String message) {}

    // ---- Personas ----

    @GetMapping("/api/runner/machines")
    List<RunnerService.MachineView> machines(@AuthenticationPrincipal AuthenticatedMember who) {
        return runners.machines(who);
    }

    @PatchMapping("/api/runner/machines/{id}")
    RunnerService.MachineView update(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable UUID id, @RequestBody UpdateRequest r) {
        return runners.update(who, id, r.paused(), r.roles(), r.capacity(), r.dailyMinutes(), Boolean.TRUE.equals(r.noDailyLimit()));
    }

    @DeleteMapping("/api/runner/machines/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable UUID id) {
        runners.delete(who, id);
    }

    /** {@code noDailyLimit}: quita el tope de tiempo por día. */
    record UpdateRequest(Boolean paused, List<String> roles, Integer capacity, Integer dailyMinutes, Boolean noDailyLimit) {}
}
