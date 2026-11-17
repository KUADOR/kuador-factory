package ec.kuador.board.sync.infrastructure.web;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.shared.error.ForbiddenException;
import ec.kuador.board.sync.application.MergeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** «Aprobar y fusionar»: quien aprueba la puerta fusiona el PR de la tarjeta desde el tablero. */
@RestController
class MergeController {

    private final MergeService merges;

    MergeController(MergeService merges) {
        this.merges = merges;
    }

    record MergeRequest(@NotBlank String repository, @Min(1) int number, boolean approve) {}

    @org.springframework.web.bind.annotation.GetMapping("/api/issues/{key}/pulls")
    java.util.List<MergeService.CardPull> pulls(@PathVariable String key) {
        return merges.pulls(key.toUpperCase(java.util.Locale.ROOT));
    }

    @PostMapping("/api/issues/{key}/merge")
    MergeService.Merged merge(@PathVariable String key, @Valid @RequestBody MergeRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        if (who.agent() || who.runner()) throw new ForbiddenException("Fusiona una persona, no un agente.");
        return merges.merge(key.toUpperCase(java.util.Locale.ROOT), r.repository(), r.number(), r.approve(), who.memberId());
    }
}
