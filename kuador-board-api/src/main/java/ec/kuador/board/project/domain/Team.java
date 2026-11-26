package ec.kuador.board.project.domain;

import ec.kuador.board.shared.error.DomainException;
import java.time.Instant;
import java.util.UUID;

/**
 * Un equipo del espacio: las personas y los agentes que atienden uno o varios tableros. Se arma una vez y cada tablero
 * dice cuál lo atiende.
 */
public record Team(UUID id, String name, Instant createdAt) {

    public static final int MAX_NAME = 140;

    public Team {
        if (name == null || name.isBlank() || name.strip().length() > MAX_NAME) {
            throw new DomainException("El nombre del equipo es obligatorio y tiene máximo " + MAX_NAME + " caracteres.");
        }
        name = name.strip();
    }

    public static Team create(String name, Instant now) {
        return new Team(UUID.randomUUID(), name, now);
    }

    public Team withName(String newName) {
        return new Team(id, newName, createdAt);
    }
}
