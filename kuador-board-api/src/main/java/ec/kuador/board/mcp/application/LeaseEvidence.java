package ec.kuador.board.mcp.application;

import ec.kuador.board.issue.CardEvidence;
import ec.kuador.board.shared.tenant.TenantContext;
import org.springframework.stereotype.Component;

/** Si una máquina tiene la tarjeta prestada: su agente la está trabajando, aunque todavía no haya abierto su ejecución. */
@Component
class LeaseEvidence implements CardEvidence {

    private final RunnerStore store;

    LeaseEvidence(RunnerStore store) {
        this.store = store;
    }

    @Override
    public boolean agentWorking(String issueKey) {
        // Uno vencido (la máquina se apagó) ya no cuenta, aunque el barrido todavía no lo haya liberado.
        return store.activeLease(TenantContext.require(), issueKey).filter(l -> l.expiresAt().isAfter(java.time.Instant.now())).isPresent();
    }
}
