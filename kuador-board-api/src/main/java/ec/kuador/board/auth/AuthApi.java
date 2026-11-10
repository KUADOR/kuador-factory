package ec.kuador.board.auth;

import java.util.UUID;

/** API pública del módulo de autenticación. */
public interface AuthApi {

    /** Da o restablece el acceso web de una persona del espacio actual. */
    void grantAccess(UUID memberId, String email, String password);

    /** Si el correo ya entra a algún espacio (los correos son únicos en todo el tablero). */
    boolean emailInUse(String email);
}
