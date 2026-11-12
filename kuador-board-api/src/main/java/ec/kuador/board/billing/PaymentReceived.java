package ec.kuador.board.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Se confirmó un pago: datos del recibo. {@code requestedBy}: quien lo inició (puede ser nulo). */
public record PaymentReceived(UUID workspaceId, UUID paymentId, UUID requestedBy, String planName, int months, BigDecimal amount, String currency,
        Instant paidAt, Instant paidUntil, String providerName, String externalId) {}
