package ec.kuador.board.project;

import ec.kuador.board.shared.tenant.WorkspaceScoped;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Se creó un tablero. {@code teamRoles}: roles de agentes que su plantilla trae por defecto; {@code software}: si es
 * de software (sus agentes trabajan por defecto en la PC de una persona; en los demás, en el tablero).
 */
public record ProjectCreated(UUID eventId, UUID workspaceId, String projectKey, List<String> teamRoles, boolean software, Instant occurredAt)
        implements WorkspaceScoped {}
