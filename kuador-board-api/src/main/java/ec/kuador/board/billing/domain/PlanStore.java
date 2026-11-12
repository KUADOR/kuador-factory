package ec.kuador.board.billing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Planes (globales) y suscripciones de los espacios. */
public interface PlanStore {

    /** Todos los planes en su orden; {@code onlyActive} deja los que se venden. */
    List<Plan> plans(boolean onlyActive);

    Optional<Plan> plan(String code);

    void savePlan(Plan plan, Instant now);

    Optional<Subscription> subscription(UUID workspaceId);

    void saveSubscription(Subscription subscription, Instant now);

    /** Espacios con su suscripción (si tienen), para quien administra la plataforma. */
    List<WorkspaceLicense> licenses();

    record WorkspaceLicense(UUID workspaceId, String slug, String name, Instant createdAt, Optional<Subscription> subscription) {}
}
