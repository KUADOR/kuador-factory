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

    /** Agentes del equipo, en orden de creación. */
    List<MemberView> agents();

    /** Cambia el acceso de una persona (ver {@link MemberAccess}); {@code actorId} no puede ser ella misma. */
    MemberView setAccess(UUID memberId, MemberAccess access, UUID actorId);

    /**
     * Falla con 403 si {@code actorId} no puede gestionar a alguien de nivel {@code level}: hay que administrar el
     * espacio, y solo un dueño gestiona administradores y dueños.
     */
    void requireCanManage(UUID actorId, MemberAccess level);

    MemberView createHuman(String name, String role, String description, String avatarColor);

    /** Crea una persona con su nivel de acceso ({@code null} = el de su rol: los clientes solo miran). */
    MemberView createHuman(String name, String role, String description, String avatarColor, MemberAccess access);

    /** Crea un agente; {@code roleKey} debe existir en el catálogo del espacio. */
    MemberView createAgent(String roleKey, String name, String description, String avatarColor);
}
