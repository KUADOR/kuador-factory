package ec.kuador.board.billing.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Plan contratado por un espacio. Vale mientras está {@code ACTIVE} (y no pasó {@code paidUntil}) o en prueba
 * vigente; si no, el espacio queda con el plan gratuito hasta que pague o cambie de plan.
 */
public record Subscription(UUID workspaceId, String planCode, Status status, Instant trialEndsAt, Instant paidUntil, boolean trialUsed) {

    public enum Status {
        TRIAL,
        ACTIVE,
        PENDING_PAYMENT,
        CANCELED
    }

    public boolean inForce(Instant now) {
        return switch (status) {
            case ACTIVE -> paidUntil == null || paidUntil.isAfter(now);
            case TRIAL -> trialEndsAt != null && trialEndsAt.isAfter(now);
            case PENDING_PAYMENT, CANCELED -> false;
        };
    }

    /** Elegir {@code plan}: gratis queda activo; de pago usa la prueba si no se usó, si no queda pendiente de pago. */
    public static Subscription choose(UUID workspaceId, Plan plan, boolean trialUsed, Instant now) {
        if (plan.free()) return new Subscription(workspaceId, plan.code(), Status.ACTIVE, null, null, trialUsed);
        if (!trialUsed && plan.trialDays() > 0) {
            return new Subscription(workspaceId, plan.code(), Status.TRIAL, now.plus(java.time.Duration.ofDays(plan.trialDays())), null, true);
        }
        return new Subscription(workspaceId, plan.code(), Status.PENDING_PAYMENT, null, null, trialUsed);
    }
}
