package ec.kuador.board.agents;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * El gasto de IA llegó al 80 % o al 100 % de un tope del mes: el del espacio ({@code agentName} nulo) o el de un agente.
 * Se publica una sola vez por mes, tope y nivel; lo escucha el módulo de avisos.
 */
public record AiBudgetAlert(UUID workspaceId, String month, int level, String agentName, BigDecimal spentUsd, BigDecimal limitUsd) {}
