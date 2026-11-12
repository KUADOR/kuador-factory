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

    // ---- Avisos de vencimiento ----

    /** Días antes del vencimiento en que se avisa (configuración de la plataforma), de mayor a menor. */
    List<Integer> reminderDays();

    void saveReminderDays(List<Integer> days, Instant now);

    /** Suscripciones con vencimiento (prueba o periodo pagado) entre {@code from} y {@code to}. */
    List<Subscription> dueBetween(Instant from, Instant to);

    /** Registra el aviso si no se había enviado; {@code true} si este llamado lo registró. */
    boolean markNotice(UUID workspaceId, String kind, Instant dueAt, int daysBefore, Instant now);
}
