package ec.kuador.board.member;

/**
 * Qué puede hacer un miembro en el espacio, de más a menos.
 * <ul>
 *   <li>{@code OWNER}: además de administrar, el plan y los pagos, y quién administra. Siempre queda al menos uno.</li>
 *   <li>{@code ADMIN}: el equipo y las invitaciones, el catálogo, la clave de IA, GitHub, los repositorios, la
 *       auditoría y borrar tableros. Gestiona a quienes editan o miran, no a otros administradores.</li>
 *   <li>{@code EDIT}: trabaja en los tableros. Los agentes siempre tienen este nivel.</li>
 *   <li>{@code VIEW}: solo mira el avance (lo habitual para un cliente); además puede aprobar o rechazar en las
 *       puertas donde su rol es aprobador.</li>
 * </ul>
 */
public enum MemberAccess {
    OWNER,
    ADMIN,
    EDIT,
    VIEW;

    /** Puede escribir en los tableros. */
    public boolean canEdit() {
        return this != VIEW;
    }

    /** Administra el espacio (dueños y administradores). */
    public boolean administers() {
        return this == OWNER || this == ADMIN;
    }

    /** Acceso por defecto al crear o invitar una persona: los clientes solo miran; nadie nace administrador. */
    public static MemberAccess defaultFor(String role) {
        return role != null && (role.equals("CLIENTE") || role.equals("CLIENT")) ? VIEW : EDIT;
    }
}
