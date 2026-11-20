package ec.kuador.board.agents;

import java.util.UUID;

/**
 * Un agente revisor cerró una puerta: la aprobó (y, si la puerta lo pide, fusionó sus PR) o la devolvió. Cuando la deja a
 * una persona no se publica: ese aviso ya lo da la asignación. Lo escucha el módulo de avisos (Telegram, correo, push).
 * {@code verdict}: APPROVED, MERGED o RETURNED. {@code summary}: la revisión del agente, para leerla en el aviso.
 */
public record GateReviewed(UUID workspaceId, String projectKey, String issueKey, String agentName, String gateName, String verdict,
        String summary) {

    public static final String APPROVED = "APPROVED";
    public static final String MERGED = "MERGED";
    public static final String RETURNED = "RETURNED";
}
