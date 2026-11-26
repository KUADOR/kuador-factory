package ec.kuador.board.sync.application;

import ec.kuador.board.issue.CardEvidence;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Los PR abiertos de la tarjeta: mientras sigan sin fusionar, la tarjeta no pasa una puerta hacia adelante (se aprueba
 * fusionando o se rechaza). Si GitHub no responde no frena el tablero: lo que no se puede leer no se inventa.
 */
@Component
class PullEvidence implements CardEvidence {

    private static final Logger log = LoggerFactory.getLogger(PullEvidence.class);

    private final CodeReviewService code;

    PullEvidence(CodeReviewService code) {
        this.code = code;
    }

    @Override
    public List<String> unmergedChanges(String projectKey, String issueKey) {
        try {
            return code.pullRequests(projectKey, issueKey).stream()
                    .filter(p -> !p.merged() && "open".equalsIgnoreCase(p.state()) && !code.justMerged(p.repository(), p.number()))
                    .map(p -> p.repository() + " #" + p.number())
                    .toList();
        } catch (RuntimeException e) {
            log.info("No se pudieron leer los PR de {} antes de pasar la puerta: {}", issueKey, e.getMessage());
            return List.of();
        }
    }
}
