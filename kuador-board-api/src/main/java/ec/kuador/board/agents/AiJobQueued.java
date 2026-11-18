package ec.kuador.board.agents;

import java.util.UUID;

/** Hay un trabajo de IA para la máquina de esa persona: que su runner pida. */
public record AiJobQueued(UUID workspaceId, UUID memberId) {}
