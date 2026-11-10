package ec.kuador.board.agents.application;

import ec.kuador.board.agents.AgentWorkApi;
import ec.kuador.board.agents.domain.AgentRun;
import ec.kuador.board.agents.domain.AgentRunRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ejecuciones de agentes que corren en el cliente de IA de una persona (ver {@link AgentRun#isLocal()}). */
@Service
class LocalAgentWork implements AgentWorkApi {

    private static final int SUMMARY_MAX = 500;

    private final AgentRunRepository runs;
    private final Clock clock;

    LocalAgentWork(AgentRunRepository runs, Clock clock) {
        this.runs = runs;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void started(UUID agentId, String issueKey, String projectKey, UUID supervisorId, String client) {
        if (running(agentId, issueKey) != null) return;
        runs.save(AgentRun.startLocal(agentId, issueKey, projectKey, supervisorId, client, clock.instant()));
    }

    @Override
    @Transactional
    public void finished(UUID agentId, String issueKey, String summary) {
        AgentRun run = running(agentId, issueKey);
        if (run == null) return;
        String text = summary == null || summary.isBlank() ? null : summary.strip();
        if (text != null && text.length() > SUMMARY_MAX) text = text.substring(0, SUMMARY_MAX) + "…";
        runs.save(run.succeeded(run.model(), 0, 0, text, clock.instant()));
    }

    private AgentRun running(UUID agentId, String issueKey) {
        return runs.findRecent(agentId, issueKey, 5).stream()
                .filter(r -> r.status() == AgentRun.Status.RUNNING && r.isLocal())
                .findFirst().orElse(null);
    }
}
