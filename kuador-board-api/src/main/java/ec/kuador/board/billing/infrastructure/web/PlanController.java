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
    private final ec.kuador.board.billing.application.LicenseReminders reminders;

    PlanController(BillingService billing, ec.kuador.board.billing.application.LicenseReminders reminders) {
        this.billing = billing;
        this.reminders = reminders;
    }

    @GetMapping("/api/public/plans")
    List<Plan> plans() {
        return billing.sellablePlans();
    }

    /** 204 si el espacio no tiene planes (instalación propia, sin límites). */
    @GetMapping("/api/workspace/plan")
    ResponseEntity<WorkspacePlanResponse> current() {
        int warn = reminders.days().stream().findFirst().orElse(0);
        return billing.current().map(p -> WorkspacePlanResponse.from(p, warn)).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PutMapping("/api/workspace/plan")
    WorkspacePlanResponse change(@Valid @RequestBody ChangeRequest r) {
        return WorkspacePlanResponse.from(billing.change(r.plan()), reminders.days().stream().findFirst().orElse(0));
    }

    record ChangeRequest(@NotBlank @Size(max = 30) String plan) {}

    /** {@code warnDays}: con cuántos días de anticipación se avisa el vencimiento (para la franja del tablero). */
    record WorkspacePlanResponse(Plan plan, Plan effective, Subscription.Status status, Instant trialEndsAt, Instant paidUntil, boolean trialUsed,
            int warnDays) {
        static WorkspacePlanResponse from(BillingService.WorkspacePlan p, int warnDays) {
            Subscription s = p.subscription();
            return new WorkspacePlanResponse(p.plan(), p.effective(), s.status(), s.trialEndsAt(), s.paidUntil(), s.trialUsed(), warnDays);
        }
    }
}
