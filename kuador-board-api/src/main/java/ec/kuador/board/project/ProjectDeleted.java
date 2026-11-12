package ec.kuador.board.project;

import ec.kuador.board.shared.tenant.WorkspaceScoped;
import java.time.Instant;
import java.util.UUID;

/**
 * Se borró un tablero con todo lo que tenía. La base ya borró en cascada sus columnas, tarjetas, comentarios,
 * documentos y conexiones; cada módulo borra con este evento lo suyo que no cuelga del tablero (actividad,
 * corridas de agentes, adjuntos y sus archivos).
 */
public record ProjectDeleted(UUID eventId, UUID workspaceId, String projectKey, String name, UUID actorId, Instant occurredAt) implements WorkspaceScoped {}
