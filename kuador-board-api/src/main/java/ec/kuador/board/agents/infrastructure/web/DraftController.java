package ec.kuador.board.agents.infrastructure.web;

import ec.kuador.board.agents.application.DraftService;
import ec.kuador.board.auth.AuthenticatedMember;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Redactar con IA los documentos base (o el objetivo) de un tablero: en el servidor o en la máquina de la persona. */
@RestController
class DraftController {

    private final DraftService drafts;

    DraftController(DraftService drafts) {
        this.drafts = drafts;
    }

    @GetMapping("/api/projects/{key}/drafting")
    DraftService.Plan plan(@PathVariable String key) {
        return drafts.plan(key);
    }

    /** {@code step}: QUESTIONS, DRAFT o REFINE. {@code local}: lo resuelve el runner de la persona con su suscripción. */
    @PostMapping("/api/projects/{key}/drafting/{step}")
    DraftService.Job ask(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable String key, @PathVariable String step,
            @RequestParam(defaultValue = "false") boolean local, @RequestBody DraftService.Input in) {
        return drafts.ask(who, key, DraftService.Step.valueOf(step.toUpperCase(java.util.Locale.ROOT)), in, local);
    }

    @GetMapping("/api/drafting/jobs/{id}")
    DraftService.Job job(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable UUID id) {
        return drafts.job(who, id);
    }

    @PostMapping("/api/projects/{key}/drafting/approve")
    Map<String, Object> approve(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable String key, @RequestBody Approve r) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(drafts.approve(who, key, r.documents(), r.goal(), r.pieces(), r.cards()));
        if (Boolean.TRUE.equals(r.kitPullRequest())) out.putAll(drafts.proposeKit(who, key));
        return out;
    }

    /** {@code pieces}, {@code cards} y {@code kitPullRequest}: al aprobar el reconocimiento de un proyecto existente. */
    record Approve(List<DraftService.ApprovedDoc> documents, String goal, List<ec.kuador.board.sync.RepositoriesApi.ExistingPiece> pieces,
            List<DraftService.SuggestedCard> cards, Boolean kitPullRequest) {}
}
