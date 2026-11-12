package ec.kuador.board.billing.infrastructure.web;

import ec.kuador.board.billing.application.BillingService;
import ec.kuador.board.billing.domain.Plan;
import ec.kuador.board.billing.domain.Subscription;
import ec.kuador.board.shared.error.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administración de la plataforma (solo ROLE_PLATFORM): planes que se venden y licencia de cada espacio. */
@RestController
@RequestMapping("/api/platform")
class PlatformController {

    private final BillingService billing;
    private final ec.kuador.board.billing.application.LicenseReminders reminders;

    PlatformController(BillingService billing, ec.kuador.board.billing.application.LicenseReminders reminders) {
        this.billing = billing;
        this.reminders = reminders;
    }

    record BillingSettings(List<Integer> reminderDays) {}

    @GetMapping("/billing-settings")
    BillingSettings settings() {
        return new BillingSettings(reminders.days());
    }

    @PutMapping("/billing-settings")
    BillingSettings saveSettings(@RequestBody BillingSettings r) {
        return new BillingSettings(reminders.saveDays(r.reminderDays()));
    }

    @GetMapping("/plans")
    List<Plan> plans() {
        return billing.allPlans();
    }

    /** Crea o actualiza el plan {@code code}. */
    @PutMapping("/plans/{code}")
    Plan save(@PathVariable String code, @Valid @RequestBody PlanRequest r) {
        return billing.savePlan(new Plan(code, r.name(), r.tagline(), r.priceMonthly(), r.currency(), r.features(), r.maxMembers(),
                r.maxProjects(), r.factory(), r.trialDays(), r.highlighted(), r.sortOrder(), r.active()));
    }

    @GetMapping("/workspaces")
    List<LicenseResponse> workspaces() {
        return billing.licenses().stream().map(l -> {
            Subscription s = l.subscription().orElse(null);
            return new LicenseResponse(l.workspaceId(), l.slug(), l.name(), l.createdAt(), s == null ? null : s.planCode(),
                    s == null ? null : s.status(), s == null ? null : s.trialEndsAt(), s == null ? null : s.paidUntil());
        }).toList();
    }

    @PutMapping("/workspaces/{workspaceId}/license")
    LicenseResponse license(@PathVariable UUID workspaceId, @Valid @RequestBody LicenseRequest r) {
        Subscription.Status status;
        try {
            status = Subscription.Status.valueOf(r.status());
        } catch (IllegalArgumentException e) {
            throw new DomainException("Estado de licencia desconocido: " + r.status() + ".");
        }
        Subscription s = billing.setLicense(workspaceId, r.plan(), status, r.trialEndsAt(), r.paidUntil());
        return billing.licenses().stream().filter(l -> l.workspaceId().equals(workspaceId)).findFirst()
                .map(l -> new LicenseResponse(l.workspaceId(), l.slug(), l.name(), l.createdAt(), s.planCode(), s.status(), s.trialEndsAt(), s.paidUntil()))
                .orElseThrow();
    }

    record PlanRequest(
            @NotBlank @Size(max = 60) String name,
            @NotBlank @Size(max = 200) String tagline,
            @NotNull BigDecimal priceMonthly,
            @Size(max = 3) String currency,
            List<@Size(max = 120) String> features,
            Integer maxMembers,
            Integer maxProjects,
            boolean factory,
            int trialDays,
            boolean highlighted,
            int sortOrder,
            boolean active) {}

    record LicenseRequest(@NotBlank @Size(max = 30) String plan, @NotBlank String status, Instant trialEndsAt, Instant paidUntil) {}

    record LicenseResponse(UUID workspaceId, String slug, String name, Instant createdAt, String plan, Subscription.Status status,
            Instant trialEndsAt, Instant paidUntil) {}
}
