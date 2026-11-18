package ec.kuador.board.routine.infrastructure.web;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.routine.application.RoutineService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Rutinas de un tablero: las configura una persona que edita; el webhook es público y lleva su secreto. */
@RestController
class RoutineController {

    private final RoutineService routines;

    RoutineController(RoutineService routines) {
        this.routines = routines;
    }

    @GetMapping("/api/projects/{key}/routines")
    List<RoutineService.View> list(@PathVariable String key) {
        return routines.list(key);
    }

    @PostMapping("/api/projects/{key}/routines")
    @ResponseStatus(HttpStatus.CREATED)
    RoutineService.View create(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable String key, @RequestBody RoutineService.Input in) {
        return routines.create(who, key, in);
    }

    @PutMapping("/api/routines/{id}")
    RoutineService.View update(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable UUID id, @RequestBody RoutineService.Input in) {
        return routines.update(who, id, in);
    }

    @DeleteMapping("/api/routines/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable UUID id) {
        routines.delete(who, id);
    }

    @PostMapping("/api/routines/{id}/run")
    RoutineService.Run run(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable UUID id) {
        return routines.runNow(who, id);
    }

    /** Crea (o cambia) el secreto del webhook; se muestra una sola vez. */
    @PostMapping("/api/routines/{id}/webhook")
    Map<String, String> webhook(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable UUID id) {
        return Map.of("secret", routines.rotateWebhook(who, id));
    }

    /** Público: lo llama un formulario u otra app con el secreto en «X-Kuador-Secret». 404 si no corresponde. */
    @PostMapping("/api/routines/{id}/hook")
    ResponseEntity<RoutineService.Run> hook(@PathVariable UUID id, @RequestHeader(value = "X-Kuador-Secret", required = false) String secret,
            @RequestBody(required = false) Map<String, Object> body) {
        Object text = body == null ? null : body.getOrDefault("text", null);
        return routines.fromWebhook(id, secret, text == null ? null : text.toString()).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
