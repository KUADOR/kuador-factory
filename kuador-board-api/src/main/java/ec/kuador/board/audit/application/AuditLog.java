package ec.kuador.board.audit.application;

import ec.kuador.board.audit.domain.AuditActions;
import ec.kuador.board.audit.domain.AuditEntry;
import ec.kuador.board.audit.domain.AuditStore;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Registra y consulta la auditoría. Registrar nunca rompe la petición: si falla, queda en el log del servidor. */
@Service
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
    public static final int MAX_PAGE = 200;
    public static final int MAX_EXPORT = 20_000;

    private final AuditStore store;
    private final TransactionTemplate tx;
    private final Clock clock;

    AuditLog(AuditStore store, PlatformTransactionManager txManager, Clock clock) {
        this.store = store;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        // Siempre en su propia transacción: lo que pasó queda registrado aunque la petición se haya revertido.
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Record(UUID workspaceId, UUID actorId, String actorName, String actorKind, String method, String action, String target, int status,
            String ip, String userAgent) {}

    public void record(Record r) {
        try {
            AuditEntry.Category category = AuditActions.meaning(r.action(), r.status()).category();
            AuditEntry entry = new AuditEntry(UUID.randomUUID(), r.workspaceId(), r.actorId(), r.actorName(), r.actorKind(), r.action(), category,
                    r.target(), r.status(), r.ip(), r.userAgent(), clock.instant());
            tx.executeWithoutResult(s -> store.append(entry));
        } catch (RuntimeException e) {
            log.warn("No se pudo registrar en la auditoría {} {}: {}", r.action(), r.target(), e.getMessage());
        }
    }

    /** Una línea para mostrar: la entrada con su etiqueta y si salió bien. */
    public record View(UUID id, Instant occurredAt, UUID workspaceId, UUID actorId, String actorName, String actorKind, String label, String action,
            String category, String target, int status, String outcome, String ip, String userAgent) {

        static View of(AuditEntry e) {
            String outcome = e.status() < 400 ? "OK" : e.status() == 401 || e.status() == 403 ? "DENIED" : "FAILED";
            return new View(e.id(), e.occurredAt(), e.workspaceId(), e.actorId(), e.actorName(), e.actorKind(),
                    AuditActions.meaning(e.action(), e.status()).label(), e.action(), e.category().name(), e.target(), e.status(), outcome, e.ip(),
                    e.userAgent());
        }
    }

    public record Page(List<View> items, Instant next) {}

    /** La auditoría del espacio actual. */
    public Page workspace(AuditEntry.Category category, UUID actorId, String text, Instant from, Instant before, int limit) {
        return page(new AuditStore.Query(TenantContext.require(), false, category, actorId, text, from, before, clamp(limit)));
    }

    /** Toda la plataforma (quien la administra), o un espacio. */
    public Page platform(UUID workspaceId, AuditEntry.Category category, String text, Instant from, Instant before, int limit) {
        return page(new AuditStore.Query(workspaceId, workspaceId == null, category, null, text, from, before, clamp(limit)));
    }

    public List<View> exportWorkspace(AuditEntry.Category category, UUID actorId, String text, Instant from) {
        AuditStore.Query q = new AuditStore.Query(TenantContext.require(), false, category, actorId, text, from, null, MAX_EXPORT);
        return tx.execute(s -> store.find(q)).stream().map(View::of).toList();
    }

    private Page page(AuditStore.Query q) {
        List<AuditEntry> found = tx.execute(s -> store.find(q));
        List<View> items = found.stream().map(View::of).toList();
        Instant next = found.size() == q.limit() ? found.getLast().occurredAt() : null;
        return new Page(items, next);
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit <= 0 ? 50 : limit, MAX_PAGE));
    }
}
