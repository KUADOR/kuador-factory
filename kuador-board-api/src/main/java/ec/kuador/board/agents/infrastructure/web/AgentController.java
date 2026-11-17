package ec.kuador.board.agents.infrastructure.web;

import ec.kuador.board.agents.domain.AgentRun;
import ec.kuador.board.agents.domain.AgentRunRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AgentController {

    private final AgentRunRepository runs;
    private final ec.kuador.board.agents.application.AgentRetry retry;
    private final ec.kuador.board.agents.application.AiKeyProvider keys;
    private final String modelName;
    private final ec.kuador.board.agents.application.AiUsage usage;

    AgentController(AgentRunRepository runs, ec.kuador.board.agents.application.AiKeyProvider keys, @Value("${board.agents.model:claude-opus-5-5}") String modelName,
            ec.kuador.board.agents.application.AgentRetry retry, ec.kuador.board.agents.application.AiUsage usage) {
        this.usage = usage;
        this.retry = retry;
        this.runs = runs;
        this.keys = keys;
        this.modelName = modelName;
    }

    /** Qué roles automatiza el tablero lo dice el catálogo (automation != NONE), no esta respuesta. */
    record StatusResponse(boolean enabled, String reason, String model) {}

    @GetMapping("/api/agents/status")
    StatusResponse status() {
        // Los agentes del tablero trabajan con la clave de IA del espacio: sin ella, no hay automatización.
        boolean enabled = keys.currentKey().isPresent();
        return new StatusResponse(enabled, enabled ? null : "Falta la clave de IA del espacio: ponla en Conexiones → IA.", modelName);
    }

    /** Ejecuciones recientes, de un agente y/o un issue. */
    /** Vuelve a lanzar un trabajo que falló o se saltó (el issue sigue asignado al agente). */
    @org.springframework.web.bind.annotation.PostMapping("/api/agents/runs/{id}/retry")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    void retry(@org.springframework.web.bind.annotation.PathVariable UUID id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal(expression = "memberId()") UUID requesterId) {
        retry.retry(id, requesterId);
    }

    /** Una ejecución con lo que costó ({@code costUsd} nulo si fue local o su modelo no tiene precio configurado). */
    record RunView(UUID id, UUID agentId, String issueKey, String projectKey, UUID requestedBy, AgentRun.Status status, String model,
            Integer inputTokens, Integer outputTokens, Integer cacheReadTokens, Integer cacheWriteTokens, String summary, String error,
            java.time.Instant createdAt, java.time.Instant finishedAt, java.math.BigDecimal costUsd) {

        static RunView of(AgentRun r, java.math.BigDecimal cost) {
            return new RunView(r.id(), r.agentId(), r.issueKey(), r.projectKey(), r.requestedBy(), r.status(), r.model(), r.inputTokens(), r.outputTokens(),
                    r.cacheReadTokens(), r.cacheWriteTokens(), r.summary(), r.error(), r.createdAt(), r.finishedAt(), cost);
        }
    }

    @GetMapping("/api/agents/runs")
    List<RunView> runs(
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) String issueKey,
            @RequestParam(defaultValue = "20") int limit) {
        // Solo los trabajos de los tableros que ve quien pide.
        List<AgentRun> visible = runs.findRecent(agentId, issueKey, Math.clamp(limit, 1, 100)).stream()
                .filter(r -> ec.kuador.board.shared.tenant.BoardScope.allowsIssue(r.issueKey())).toList();
        java.util.Map<UUID, java.math.BigDecimal> costs = usage.costs(visible);
        return visible.stream().map(r -> RunView.of(r, costs.get(r.id()))).toList();
    }
}
