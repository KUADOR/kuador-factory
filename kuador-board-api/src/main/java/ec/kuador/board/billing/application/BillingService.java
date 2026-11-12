package ec.kuador.board.billing.application;

import ec.kuador.board.billing.BillingApi;
import ec.kuador.board.billing.domain.Plan;
import ec.kuador.board.billing.domain.PlanStore;
import ec.kuador.board.billing.domain.Subscription;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Planes y suscripciones. El plan vigente de un espacio es el contratado mientras está activo o en prueba; si
 * venció o falta el pago, rige el plan gratuito (el primero de precio 0). Bajar de plan no borra nada: solo
 * impide crear más de lo que el plan permite.
 */
@Service
public class BillingService implements BillingApi {

    private final PlanStore store;
    private final Clock clock;

    BillingService(PlanStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Plan del espacio y el que rige hoy ({@code effective} difiere si la prueba venció o falta el pago). */
    public record WorkspacePlan(Subscription subscription, Plan plan, Plan effective) {}

    // ---- Para otros módulos ----

    @Override
    @Transactional(readOnly = true)
    public void requireRoom(Limit limit, long current) {
        effectivePlan(TenantContext.require()).ifPresent(plan -> {
            Integer max = limit == Limit.MEMBERS ? plan.maxMembers() : plan.maxProjects();
            if (max != null && current >= max) {
                String what = limit == Limit.MEMBERS ? max + " persona(s)" : max + " tablero(s)";
                throw new DomainException("Tu plan " + plan.name() + " permite hasta " + what + ". Cambia de plan en Espacio de trabajo → Plan para sumar más.");
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public void requireFactory(String what) {
        effectivePlan(TenantContext.require()).filter(plan -> !plan.factory()).ifPresent(plan -> {
            throw new DomainException(what + " es parte de la fábrica de software, que no incluye tu plan " + plan.name()
                    + ". Cambia de plan en Espacio de trabajo → Plan.");
        });
    }

    @Override
    @Transactional
    public void subscribe(UUID workspaceId, String planCode) {
        Plan plan = sellable(planCode == null || planCode.isBlank() ? freePlan().map(Plan::code).orElse(null) : planCode);
        store.saveSubscription(Subscription.choose(workspaceId, plan, false, clock.instant()), clock.instant());
    }

    // ---- Espacio actual ----

    @Transactional(readOnly = true)
    public Optional<WorkspacePlan> current() {
        UUID ws = TenantContext.require();
        return store.subscription(ws).map(s -> {
            Plan plan = store.plan(s.planCode()).orElseThrow();
            return new WorkspacePlan(s, plan, effective(s, plan));
        });
    }

    /** Cambio de plan desde el espacio: lo gratis rige ya; lo de pago usa la prueba si no se usó o queda pendiente de pago. */
    @Transactional
    public WorkspacePlan change(String planCode) {
        UUID ws = TenantContext.require();
        Plan plan = sellable(planCode);
        Subscription before = store.subscription(ws)
                .orElseThrow(() -> new DomainException("Este espacio no tiene planes: es una instalación propia sin límites."));
        Subscription next = before.planCode().equals(plan.code()) && before.inForce(clock.instant())
                ? before
                : Subscription.choose(ws, plan, before.trialUsed(), clock.instant());
        store.saveSubscription(next, clock.instant());
        return new WorkspacePlan(next, plan, effective(next, plan));
    }

    // ---- Público y plataforma ----

    @Transactional(readOnly = true)
    public List<Plan> sellablePlans() {
        return store.plans(true);
    }

    @Transactional(readOnly = true)
    public List<Plan> allPlans() {
        return store.plans(false);
    }

    @Transactional
    public Plan savePlan(Plan plan) {
        store.savePlan(plan, clock.instant());
        return plan;
    }

    @Transactional(readOnly = true)
    public List<PlanStore.WorkspaceLicense> licenses() {
        return store.licenses();
    }

    /** Ajuste manual de quien administra la plataforma (p. ej. registrar un pago por transferencia). */
    @Transactional
    public Subscription setLicense(UUID workspaceId, String planCode, Subscription.Status status, Instant trialEndsAt, Instant paidUntil) {
        Plan plan = store.plan(planCode).orElseThrow(() -> new NotFoundException("No existe el plan " + planCode + "."));
        if (status == Subscription.Status.TRIAL && trialEndsAt == null) throw new DomainException("Una prueba necesita fecha de fin.");
        boolean trialUsed = store.subscription(workspaceId).map(Subscription::trialUsed).orElse(false) || status == Subscription.Status.TRIAL;
        Subscription s = new Subscription(workspaceId, plan.code(), status, status == Subscription.Status.TRIAL ? trialEndsAt : null,
                status == Subscription.Status.ACTIVE ? paidUntil : null, trialUsed);
        store.saveSubscription(s, clock.instant());
        return s;
    }

    private Optional<Plan> effectivePlan(UUID workspaceId) {
        return store.subscription(workspaceId).map(s -> effective(s, store.plan(s.planCode()).orElseThrow()));
    }

    private Plan effective(Subscription s, Plan plan) {
        Instant now = clock.instant();
        return s.inForce(now) ? plan : freePlan().orElse(plan);
    }

    private Optional<Plan> freePlan() {
        return store.plans(true).stream().filter(Plan::free).min(Comparator.comparingInt(Plan::sortOrder));
    }

    private Plan sellable(String code) {
        return store.plan(code == null ? "" : code.strip().toUpperCase()).filter(Plan::active)
                .orElseThrow(() -> new DomainException("Ese plan no está disponible. Elige uno de la lista."));
    }
}
