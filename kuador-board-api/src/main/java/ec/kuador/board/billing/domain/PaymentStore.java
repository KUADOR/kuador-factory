package ec.kuador.board.billing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Pasarelas (credenciales cifradas) y pagos. */
public interface PaymentStore {

    record StoredProvider(String code, String type, String name, PaymentProvider.Mode mode, String credentialsEncrypted, boolean active, int sortOrder) {}

    List<StoredProvider> providers();

    Optional<StoredProvider> provider(String code);

    void saveProvider(StoredProvider provider, Instant now);

    void save(Payment payment);

    Optional<Payment> find(UUID id);

    Optional<Payment> findByExternal(String providerCode, String externalId);

    void setExternalId(UUID id, String externalId);

    /** Marca pagado solo si no lo estaba; {@code true} si este llamado lo marcó (evita extender dos veces). */
    boolean markPaid(UUID id, Instant now);

    void markFailed(UUID id);

    List<Payment> recent(UUID workspaceId, int limit);
}
