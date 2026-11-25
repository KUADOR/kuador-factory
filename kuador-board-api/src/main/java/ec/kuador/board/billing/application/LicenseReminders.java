package ec.kuador.board.billing.application;

import ec.kuador.board.billing.LicenseExpired;
import ec.kuador.board.billing.LicenseExpiring;
import ec.kuador.board.billing.domain.Plan;
import ec.kuador.board.billing.domain.PlanStore;
import ec.kuador.board.billing.domain.Subscription;
import ec.kuador.board.shared.error.DomainException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Avisa antes de que venza una prueba o un periodo pagado (los días los configura la plataforma) y el día que
 * vence. Cada aviso sale una sola vez por vencimiento: si se pasaron varios días sin revisar, va solo el más
 * cercano, no todos juntos. Quién lo recibe y por dónde lo decide el módulo de avisos.
 */
@Service
public class LicenseReminders {

    private static final Logger log = LoggerFactory.getLogger(LicenseReminders.class);
    /** Un vencimiento más viejo que esto ya no se avisa (p. ej. al activar la función por primera vez). */
    static final Duration EXPIRED_WINDOW = Duration.ofDays(3);

    private final PlanStore store;
    private final ec.kuador.board.shared.cluster.ClusterLock lock;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    LicenseReminders(PlanStore store, ApplicationEventPublisher events, Clock clock, ec.kuador.board.shared.cluster.ClusterLock lock) {
        this.lock = lock;
        this.store = store;
        this.events = events;
        this.clock = clock;
    }

    public List<Integer> days() {
        return store.reminderDays();
    }

    /** Días de anticipación: entre 1 y 60, hasta 6 valores distintos. */
    public List<Integer> saveDays(List<Integer> days) {
        List<Integer> clean = days == null ? List.of() : days.stream().distinct().sorted(java.util.Comparator.reverseOrder()).toList();
        if (clean.size() > 6 || clean.stream().anyMatch(d -> d == null || d < 1 || d > 60)) {
            throw new DomainException("Los avisos van entre 1 y 60 días antes, hasta 6 valores.");
        }
        store.saveReminderDays(clean, clock.instant());
        return clean;
    }

    @Scheduled(fixedDelayString = "${board.billing.reminders-every:3600000}", initialDelayString = "${board.billing.reminders-delay:60000}")
    void scheduledOnOneReplica() {
        lock.runExclusive("vencimientos", java.time.Duration.ofMinutes(15), this::scheduled);
    }

    void scheduled() {
        try {
            int sent = run();
            if (sent > 0) log.info("{} aviso(s) de vencimiento", sent);
        } catch (RuntimeException e) {
            log.error("Falló la revisión de vencimientos: {}", e.getMessage());
        }
    }

    /** Revisa vencimientos y publica los avisos que tocan. Devuelve cuántos. */
    public int run() {
        Instant now = clock.instant();
        List<Integer> days = store.reminderDays();
        int max = days.isEmpty() ? 0 : days.getFirst();
        String fallback = store.plans(true).stream().filter(Plan::free).min(java.util.Comparator.comparingInt(Plan::sortOrder))
                .map(Plan::name).orElse("gratuito");
        int sent = 0;
        for (Subscription s : store.dueBetween(now.minus(EXPIRED_WINDOW), now.plus(Duration.ofDays(max)))) {
            boolean trial = s.status() == Subscription.Status.TRIAL;
            Instant due = trial ? s.trialEndsAt() : s.paidUntil();
            String planName = store.plan(s.planCode()).map(Plan::name).orElse(s.planCode());
            if (!due.isAfter(now)) {
                if (store.markNotice(s.workspaceId(), "EXPIRED", due, 0, now)) {
                    events.publishEvent(new LicenseExpired(s.workspaceId(), planName, due, trial, fallback));
                    sent++;
                }
                continue;
            }
            int daysLeft = (int) Math.ceil(Duration.between(now, due).toMinutes() / (24.0 * 60));
            // El umbral más chico que todavía alcanza: con 2 días restantes y avisos a 7, 3 y 1, toca el de 3.
            Integer threshold = days.stream().filter(d -> d >= daysLeft).min(Integer::compare).orElse(null);
            if (threshold != null && store.markNotice(s.workspaceId(), "EXPIRING", due, threshold, now)) {
                events.publishEvent(new LicenseExpiring(s.workspaceId(), planName, due, daysLeft, trial, fallback));
                sent++;
            }
        }
        return sent;
    }
}
