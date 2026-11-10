package ec.kuador.board.docs;

import ec.kuador.board.shared.tenant.WorkspaceScoped;
import java.time.Instant;
import java.util.UUID;

/**
 * Se guardó una versión nueva de un documento ({@code version} 1 = recién creado). La escuchan el
 * historial y, más adelante, la sincronización con el repositorio y los agentes.
 * {@code issueKey} nulo = documento del proyecto.
 */
public record DocumentSaved(
        UUID eventId, UUID workspaceId, UUID documentId, String projectKey, String issueKey, String kind, String title, String path,
        int version, UUID actorId, Instant occurredAt) implements WorkspaceScoped {}
