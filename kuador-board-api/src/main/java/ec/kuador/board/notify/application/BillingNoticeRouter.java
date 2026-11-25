package ec.kuador.board.notify.application;

import ec.kuador.board.billing.LicenseExpired;
import ec.kuador.board.billing.LicenseExpiring;
import ec.kuador.board.billing.PaymentReceived;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.notify.domain.NoticeKind;
import ec.kuador.board.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import ec.kuador.board.shared.config.AsyncExecutors;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Avisos de cobro: el plan vence pronto, venció, o llegó un pago (recibo). Van a los dueños del espacio (rol OWNER;
 * si no hay, a las personas que editan) y el recibo también a quien pagó. Por correo y, si lo tienen, Telegram.
 */
@Component
class BillingNoticeRouter {

    private static final Logger log = LoggerFactory.getLogger(BillingNoticeRouter.class);
    private static final Locale ES = Locale.forLanguageTag("es-EC");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", ES);

    private final NotificationService notifications;
    private final MemberApi members;
    private final TransactionTemplate tx;
    private final String planUrl;
    private final ZoneId zone;

    BillingNoticeRouter(NotificationService notifications, MemberApi members, PlatformTransactionManager txManager,
            @Value("${board.web-url:http://localhost:3600}") String webUrl, @Value("${board.billing.zone:America/Guayaquil}") String zone) {
        this.notifications = notifications;
        this.members = members;
        this.tx = new TransactionTemplate(txManager);
        this.planUrl = webUrl.replaceAll("/+$", "") + "/workspace/plan";
        this.zone = ZoneId.of(zone);
    }

    @Async(AsyncExecutors.NOTICES)
    @TransactionalEventListener(fallbackExecution = true)
    void on(LicenseExpiring e) {
        String what = e.trial() ? "Tu prueba del plan " + e.planName() : "Tu plan " + e.planName();
        String when = e.daysLeft() <= 1 ? "mañana" : "en " + e.daysLeft() + " días";
        send(e.workspaceId(), Set.of(), what + " vence " + when,
                what + " vence el " + date(e.dueAt()) + ".\n\n"
                        + (e.trial() ? "Para seguir con todo lo que incluye, paga el plan antes de esa fecha."
                                : "Renueva antes de esa fecha para que nada cambie.")
                        + " Si no, el espacio pasa al plan " + e.fallbackPlanName() + ": no se borra nada, pero rigen sus límites.");
    }

    @Async(AsyncExecutors.NOTICES)
    @TransactionalEventListener(fallbackExecution = true)
    void on(LicenseExpired e) {
        String what = e.trial() ? "Terminó tu prueba del plan " + e.planName() : "Venció tu plan " + e.planName();
        send(e.workspaceId(), Set.of(), what,
                what + " (" + date(e.dueAt()) + "). Desde ahora rige el plan " + e.fallbackPlanName()
                        + ": tus tableros, tarjetas y archivos siguen ahí, pero no podrás crear más de lo que ese plan permite.\n\n"
                        + "Paga el plan para volver a tenerlo completo.");
    }

    @Async(AsyncExecutors.NOTICES)
    @TransactionalEventListener(fallbackExecution = true)
    void on(PaymentReceived e) {
        Set<UUID> extra = e.requestedBy() == null ? Set.of() : Set.of(e.requestedBy());
        String months = e.months() == 1 ? "1 mes" : e.months() + " meses";
        send(e.workspaceId(), extra, "Recibo de pago · Plan " + e.planName(),
                "Recibimos tu pago. Gracias.\n\n"
                        + "Plan: " + e.planName() + "\n"
                        + "Periodo: " + months + "\n"
                        + "Monto: " + money(e.amount(), e.currency()) + "\n"
                        + "Fecha: " + date(e.paidAt()) + "\n"
                        + "Pagado hasta: " + date(e.paidUntil()) + "\n"
                        + "Medio de pago: " + e.providerName() + (e.externalId() == null ? "" : " (referencia " + e.externalId() + ")") + "\n"
                        + "Recibo: " + e.paymentId());
    }

    /** A los dueños (o, si no hay, a quienes administran) más {@code extra}, en el espacio del aviso. */
    private void send(UUID workspaceId, Set<UUID> extra, String subject, String body) {
        try {
            TenantContext.run(workspaceId, () -> tx.executeWithoutResult(s -> {
                List<MemberView> humans = members.humans();
                Set<UUID> to = new LinkedHashSet<>();
                humans.stream().filter(MemberView::owner).map(MemberView::id).forEach(to::add);
                if (to.isEmpty()) humans.stream().filter(MemberView::administers).map(MemberView::id).forEach(to::add);
                extra.stream().filter(id -> humans.stream().anyMatch(m -> m.id().equals(id))).forEach(to::add);
                to.forEach(person -> notifications.enqueue(workspaceId, person, NoticeKind.BILLING, subject, body, planUrl));
            }));
        } catch (RuntimeException ex) {
            log.error("No se pudo preparar un aviso de cobro: {}", ex.getMessage(), ex);
        }
    }

    private String date(Instant i) {
        return i == null ? "—" : DATE.format(i.atZone(zone));
    }

    static String money(BigDecimal amount, String currency) {
        NumberFormat f = NumberFormat.getCurrencyInstance(ES);
        try {
            f.setCurrency(Currency.getInstance(currency));
        } catch (IllegalArgumentException e) {
            return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " " + currency;
        }
        return f.format(amount) + " " + currency;
    }
}
