/**
 * Autenticación. Personas: correo + contraseña → sesión. Agentes: tokens de servicio.
 * Ambos son tokens opacos {@code Authorization: Bearer ...}; en base solo se guarda su SHA-256.
 */
package ec.kuador.board.auth;
