/**
 * Avisos a personas por correo y Telegram cuando algo las necesita: les asignan un issue (p. ej. un
 * agente les pregunta), un issue espera su aprobación en una puerta o un reparto de tareas espera que
 * lo aprueben. Cada persona elige canales y avisos. Se alimenta de los eventos públicos de issue; ese
 * módulo no sabe que existe. Los envíos pasan por una cola con reintentos.
 */
package ec.kuador.board.notify;
