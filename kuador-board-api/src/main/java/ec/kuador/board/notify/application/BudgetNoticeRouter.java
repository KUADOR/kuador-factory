package ec.kuador.board.notify.application;

import ec.kuador.board.agents.AiBudgetAlert;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.notify.domain.NoticeKind;
import ec.kuador.board.shared.tenant.TenantContext;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aviso de presupuesto: el gasto de IA del mes llegó al 80 % (para decidir a tiempo) o al tope (los agentes de ese tope
 * se detienen). Va a quien administra el espacio, por los canales que tenga (correo, Telegram, push).
 */
@Component
class BudgetNoticeRouter {

    private static final Logger log = LoggerFactory.getLogger(BudgetNoticeRouter.class);

    private final NotificationService notifications;
    private final MemberApi members;
    private final TransactionTemplate tx;
    private final String aiUrl;

    BudgetNoticeRouter(NotificationService notifications, MemberApi members, PlatformTransactionManager txManager,
            @Value("${board.web-url:http://localhost:3600}") String webUrl) {
        this.notifications = notifications;
        this.members = members;
        this.tx = new TransactionTemplate(txManager);
        this.aiUrl = webUrl.replaceAll("/+$", "") + "/connections";
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(AiBudgetAlert e) {
        String who = e.agentName() == null ? "El espacio" : e.agentName();
        String spent = "USD " + e.spentUsd().setScale(2, RoundingMode.HALF_UP) + " de " + e.limitUsd().setScale(2, RoundingMode.HALF_UP);
        String subject = e.level() >= 100 ? who + " llegó a su tope de IA del mes" : who + " va en el 80 % de su tope de IA del mes";
        String body = e.level() >= 100
                ? who + " gastó " + spent + " este mes. " + (e.agentName() == null ? "Los agentes del tablero" : e.agentName())
                        + " no toman trabajo nuevo hasta que subas el tope o empiece el próximo mes. Lo cambias en Conexiones → IA."
                : who + " lleva " + spent + " este mes. Si sigue a este ritmo, llega al tope antes de fin de mes: revísalo en Conexiones → IA.";
        try {
            TenantContext.run(e.workspaceId(), () -> tx.executeWithoutResult(s -> members.humans().stream().filter(MemberView::administers)
                    .forEach(m -> notifications.enqueue(e.workspaceId(), m.id(), NoticeKind.BUDGET, subject, body, aiUrl))));
        } catch (RuntimeException ex) {
            log.error("No se pudo preparar un aviso de presupuesto: {}", ex.getMessage(), ex);
        }
    }
}
