package ec.kuador.board.member;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** API pública del módulo (espacio de trabajo actual, salvo que el contexto sea root). */
public interface MemberApi {

    boolean exists(UUID memberId);

    Optional<MemberView> find(UUID memberId);

    /** Personas del equipo, en orden de creación. */
    List<MemberView> humans();

    /** Cambia el acceso de una persona (ver {@link MemberAccess}); {@code actorId} no puede ser ella misma. */
    MemberView setAccess(UUID memberId, MemberAccess access, UUID actorId);

    MemberView createHuman(String name, String role, String description, String avatarColor);

    /** Crea un agente; {@code roleKey} debe existir en el catálogo del espacio. */
    MemberView createAgent(String roleKey, String name, String description, String avatarColor);
}
