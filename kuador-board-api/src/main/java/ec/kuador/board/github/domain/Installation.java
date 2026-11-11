package ec.kuador.board.github.domain;

import java.time.Instant;
import java.util.UUID;

/** La app instalada en una organización o cuenta de GitHub, conectada al espacio actual. */
public record Installation(UUID id, long installationId, String accountLogin, String accountType, UUID linkedBy, Instant linkedAt) {}
