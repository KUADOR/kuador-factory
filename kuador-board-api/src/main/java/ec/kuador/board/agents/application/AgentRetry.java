package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.AgentRun;
import ec.kuador.board.agents.domain.AgentRunRepository;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueAssigned;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.BoardScope;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Volver a lanzar un trabajo de agente que falló o se saltó (p. ej. tras acortar los comentarios del issue o poner la
 * clave de IA). El issue debe seguir asignado a ese agente; corre como si la persona se lo asignara de nuevo.
 */
@Service
public class AgentRetry {

    private final AgentRunRepository runs;
    private final IssueApi issues;
    private final AgentDispatcher dispatcher;
    private final Clock clock;

    AgentRetry(AgentRunRepository runs, IssueApi issues, AgentDispatcher dispatcher, Clock clock) {
        this.runs = runs;
        this.issues = issues;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public void retry(UUID runId, UUID requesterId) {
        AgentRun run = runs.findById(runId).filter(r -> BoardScope.allowsIssue(r.issueKey()))
                .orElseThrow(() -> new NotFoundException("No existe ese trabajo de agente."));
        if (run.status() != AgentRun.Status.FAILED && run.status() != AgentRun.Status.SKIPPED) {
            throw new DomainException("Solo se reintenta un trabajo que falló o se saltó.");
        }
        if (run.isLocal()) throw new DomainException("Ese trabajo corrió en la PC de una persona: se reintenta desde su cliente de IA con /pendientes.");
        UUID assignee = issues.context(run.issueKey()).map(IssueApi.IssueContext::assigneeId)
                .orElseThrow(() -> new NotFoundException("El issue " + run.issueKey() + " ya no existe."));
        if (!run.agentId().equals(assignee)) {
            throw new DomainException(run.issueKey() + " ya no está asignado a ese agente: asígnaselo de nuevo para que lo trabaje.");
        }
        dispatcher.retry(new IssueAssigned(UUID.randomUUID(), TenantContext.require(), run.issueKey(), run.projectKey(), requesterId, clock.instant(),
                assignee, assignee));
    }
}
