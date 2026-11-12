package ec.kuador.board.billing.infrastructure.web;

import ec.kuador.board.billing.application.BillingService;
import ec.kuador.board.billing.domain.Plan;
import ec.kuador.board.billing.domain.Subscription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Planes a la venta (público, para la presentación y el alta) y el plan del espacio actual. */
@RestController
class PlanController {

    private final BillingService billing;

    PlanController(BillingService billing) {
        this.billing = billing;
    }

    @GetMapping("/api/public/plans")
    List<Plan> plans() {
        return billing.sellablePlans();
    }

    /** 204 si el espacio no tiene planes (instalación propia, sin límites). */
    @GetMapping("/api/workspace/plan")
    ResponseEntity<WorkspacePlanResponse> current() {
        return billing.current().map(WorkspacePlanResponse::from).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PutMapping("/api/workspace/plan")
    WorkspacePlanResponse change(@Valid @RequestBody ChangeRequest r) {
        return WorkspacePlanResponse.from(billing.change(r.plan()));
    }

    record ChangeRequest(@NotBlank @Size(max = 30) String plan) {}

    record WorkspacePlanResponse(Plan plan, Plan effective, Subscription.Status status, Instant trialEndsAt, Instant paidUntil, boolean trialUsed) {
        static WorkspacePlanResponse from(BillingService.WorkspacePlan p) {
            Subscription s = p.subscription();
            return new WorkspacePlanResponse(p.plan(), p.effective(), s.status(), s.trialEndsAt(), s.paidUntil(), s.trialUsed());
        }
    }
}
