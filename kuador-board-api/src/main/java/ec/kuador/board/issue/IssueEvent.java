package ec.kuador.board.issue;

import ec.kuador.board.shared.tenant.WorkspaceScoped;
import java.time.Instant;
import java.util.UUID;

/**
 * Eventos públicos del módulo issue. Se publican dentro de la transacción del cambio y Spring
 * Modulith los guarda en {@code event_publication}: un listener que falla se reintenta, así ningún
 * módulo (historial, agentes) se pierde un cambio. Los enums viajan como texto para no exponer el
 * dominio interno.
 */
public sealed interface IssueEvent extends WorkspaceScoped
        permits IssueCreated, IssueUpdated, IssueMoved, IssueAssigned, IssueCommented, IssueDeleted {

    /** Identificador único del evento, para que los listeners sean idempotentes. */
    UUID eventId();

    String issueKey();

    String projectKey();

    /** Persona o agente que hizo el cambio. */
    UUID actorId();

    Instant occurredAt();
}
