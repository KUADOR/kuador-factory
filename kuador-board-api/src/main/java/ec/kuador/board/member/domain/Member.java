package ec.kuador.board.member.domain;

import ec.kuador.board.shared.error.DomainException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Un miembro del tablero. {@code role} es un identificador corto (BACKEND, QA, DEVOPS...) y
 * {@code description} explica su especialidad; para un agente será la base de su prompt.
 */
public record Member(
        UUID id, UUID workspaceId, String name, MemberKind kind, String role, String description, String avatarColor, Instant createdAt) {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern ROLE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    public Member {
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new DomainException("El nombre es obligatorio y tiene máximo 120 caracteres.");
        }
        if (kind == null) {
            throw new DomainException("Indica si el miembro es HUMAN o AGENT.");
        }
        if (role == null || !ROLE.matcher(role).matches()) {
            throw new DomainException("El rol va en mayúsculas, por ejemplo BACKEND o QA.");
        }
        if (avatarColor == null || !COLOR.matcher(avatarColor).matches()) {
            throw new DomainException("El color debe tener el formato #RRGGBB.");
        }
        name = name.strip();
    }

    public static Member create(String name, MemberKind kind, String role, String description, String avatarColor, Instant now) {
        // workspaceId lo asigna la persistencia con el espacio en contexto.
        return new Member(UUID.randomUUID(), null, name, kind, role, description, avatarColor, now);
    }
}
