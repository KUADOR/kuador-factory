package ec.kuador.board.audit.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditStore {

    void append(AuditEntry entry);

    /**
     * Filtro de búsqueda. {@code workspaceId} nulo: toda la plataforma. {@code before}: para paginar hacia atrás.
     * {@code text}: busca en la acción, el objetivo, la persona y la IP.
     */
    record Query(UUID workspaceId, boolean allWorkspaces, AuditEntry.Category category, UUID actorId, String text, Instant from, Instant before, int limit) {}

    List<AuditEntry> find(Query query);
}
