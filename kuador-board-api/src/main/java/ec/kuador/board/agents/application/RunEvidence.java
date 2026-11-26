package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.AgentRun;
import ec.kuador.board.agents.domain.AgentRunRepository;
import ec.kuador.board.issue.CardEvidence;
import org.springframework.stereotype.Component;

/** Si un agente está trabajando la tarjeta ahora: tiene una ejecución abierta (en el tablero, el MCP o una máquina). */
@Component
class RunEvidence implements CardEvidence {

    private final AgentRunRepository runs;

    RunEvidence(AgentRunRepository runs) {
        this.runs = runs;
    }

    @Override
    public boolean agentWorking(String issueKey) {
        return runs.findRecent(null, issueKey, 5).stream().anyMatch(r -> r.status() == AgentRun.Status.RUNNING);
    }
}
