package ec.kuador.board.project;

import ec.kuador.board.shared.tenant.WorkspaceScoped;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Las etapas de un tablero tienen puertas que revisa un agente: el equipo necesita un agente con cada uno de esos
 * {@code roles}, trabajando en el tablero.
 */
public record ReviewersNeeded(UUID eventId, UUID workspaceId, String projectKey, List<String> roles, Instant occurredAt) implements WorkspaceScoped {}
