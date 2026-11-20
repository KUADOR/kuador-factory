package ec.kuador.board.sync;

import java.util.UUID;

/**
 * El tablero fusionó un PR de una tarjeta (una persona con «Aprobar y fusionar» o una puerta en automático). Lo escucha
 * el vigilante de conflictos: los demás PR abiertos hacia la misma rama pueden haber quedado en conflicto.
 * {@code actorId}: quien fusionó (persona o agente revisor); firma los comentarios de lo que se devuelve.
 */
public record PullMerged(UUID workspaceId, String projectKey, String repository, int number, UUID actorId) {}
