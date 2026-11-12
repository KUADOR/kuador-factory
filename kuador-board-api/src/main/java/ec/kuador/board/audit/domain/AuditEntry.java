package ec.kuador.board.audit.domain;

import java.time.Instant;
import java.util.UUID;

/** Una línea de auditoría. {@code action}: «MÉTODO /ruta/{patrón}»; su etiqueta la da {@link AuditActions}. */
public record AuditEntry(UUID id, UUID workspaceId, UUID actorId, String actorName, String actorKind, String action, Category category,
        String target, int status, String ip, String userAgent, Instant occurredAt) {

    public enum Category {
        /** Accesos, permisos, claves, conexiones, pagos y configuración. */
        SECURITY,
        /** El trabajo del día: tarjetas, comentarios, documentos. */
        WORK
    }
}
