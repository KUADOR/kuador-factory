package ec.kuador.board.member;

/**
 * Qué puede hacer un miembro. {@code EDIT}: trabajar en el tablero. {@code VIEW}: solo mirar el
 * avance (lo habitual para un cliente); además puede aprobar o rechazar en las puertas donde su
 * rol es aprobador. Los agentes siempre editan.
 */
public enum MemberAccess {
    EDIT,
    VIEW;

    /** Acceso por defecto al crear una persona: los clientes solo miran. */
    public static MemberAccess defaultFor(String role) {
        return role != null && (role.equals("CLIENTE") || role.equals("CLIENT")) ? VIEW : EDIT;
    }
}
