package ec.kuador.board.auth.infrastructure.web;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.auth.application.RunnerPairing;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Emparejar un runner (ver RunnerPairing): el runner pide un código y lo canjea; la persona lo aprueba con su sesión. */
@RestController
class RunnerPairingController {

    private final RunnerPairing pairing;

    RunnerPairingController(RunnerPairing pairing) {
        this.pairing = pairing;
    }

    /** Público: lo llama el runner al instalarse. */
    @PostMapping("/api/runner/pair")
    RunnerPairing.Started start(@RequestBody(required = false) StartRequest r) {
        return pairing.start(r == null ? null : r.machineName());
    }

    /** Público: el runner pregunta cada pocos segundos. 202 = esperando; 410 = venció o ya se entregó. */
    @PostMapping("/api/runner/pair/token")
    ResponseEntity<?> token(@RequestBody TokenRequest r) {
        return pairing.poll(r.deviceCode())
                .<ResponseEntity<?>>map(p -> p.token() == null
                        ? ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "pending"))
                        : ResponseEntity.ok(Map.of("token", p.token())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.GONE).body(Map.of("status", "expired")));
    }

    /** La persona aprueba el código que muestra la máquina (desde la web, con su sesión). */
    @PostMapping("/api/runner/pair/approve")
    Map<String, String> approve(@AuthenticationPrincipal AuthenticatedMember who, @RequestBody ApproveRequest r) {
        return Map.of("machineName", pairing.approve(who, r.userCode()));
    }

    record StartRequest(@Size(max = 60) String machineName) {}

    record TokenRequest(@NotBlank String deviceCode) {}

    record ApproveRequest(@NotBlank String userCode) {}
}
