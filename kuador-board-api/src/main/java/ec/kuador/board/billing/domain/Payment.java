package ec.kuador.board.billing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Un pago de un espacio por {@code months} meses de un plan, con una pasarela. */
public record Payment(UUID id, UUID workspaceId, String planCode, String providerCode, BigDecimal amount, String currency, int months, Status status,
        String externalId, Instant createdAt, Instant paidAt, UUID requestedBy) {

    public enum Status {
        CREATED,
        PAID,
        FAILED
    }
}
