package ec.kuador.board.issue.domain;

import ec.kuador.board.shared.error.DomainException;
import java.time.Instant;
import java.util.UUID;

public record Comment(UUID id, UUID issueId, UUID authorId, String body, Instant createdAt) {

    public Comment {
        if (body == null || body.isBlank() || body.length() > 20_000) {
            throw new DomainException("El comentario no puede estar vacío ni pasar de 20.000 caracteres.");
        }
        if (authorId == null) {
            throw new DomainException("El comentario necesita un autor.");
        }
    }

    public static Comment create(UUID issueId, UUID authorId, String body, Instant now) {
        return new Comment(UUID.randomUUID(), issueId, authorId, body.strip(), now);
    }
}
