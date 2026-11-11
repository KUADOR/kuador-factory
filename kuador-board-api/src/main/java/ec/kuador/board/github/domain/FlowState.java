package ec.kuador.board.github.domain;

import java.time.Instant;
import java.util.UUID;

/** Estado de un solo uso para un viaje a GitHub y vuelta: ata la respuesta al espacio y a la persona que lo inició. */
public record FlowState(String state, Purpose purpose, UUID memberId, Instant expiresAt) {

    public enum Purpose {
        REGISTER,
        INSTALL
    }
}
