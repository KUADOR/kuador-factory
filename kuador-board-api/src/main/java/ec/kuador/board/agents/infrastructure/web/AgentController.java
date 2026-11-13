package ec.kuador.board.agents.infrastructure.web;

import ec.kuador.board.agents.domain.AgentRun;
import ec.kuador.board.agents.domain.AgentRunRepository;
import ec.kuador.board.agents.domain.ProductOwnerModel;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AgentController {

    private final AgentRunRepository runs;
    private final ProductOwnerModel model;
    private final String modelName;

    AgentController(AgentRunRepository runs, ProductOwnerModel model, @Value("${board.agents.model:claude-opus-5-5}") String modelName) {
        this.runs = runs;
        this.model = model;
        this.modelName = modelName;
    }

    /** Qué roles automatiza el tablero lo dice el catálogo (automation != NONE), no esta respuesta. */
    record StatusResponse(boolean enabled, String reason, String model) {}

    @GetMapping("/api/agents/status")
    StatusResponse status() {
        return new StatusResponse(model.available(), model.available() ? null : model.unavailableReason(), modelName);
    }

    /** Ejecuciones recientes, de un agente y/o un issue. */
    @GetMapping("/api/agents/runs")
    List<AgentRun> runs(
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) String issueKey,
            @RequestParam(defaultValue = "20") int limit) {
        // Solo los trabajos de los tableros que ve quien pide.
        return runs.findRecent(agentId, issueKey, Math.clamp(limit, 1, 100)).stream()
                .filter(r -> ec.kuador.board.shared.tenant.BoardScope.allowsIssue(r.issueKey())).toList();
    }
}
