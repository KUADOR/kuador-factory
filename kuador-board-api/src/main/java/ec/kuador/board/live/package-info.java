/**
 * Cambios en tiempo real: avisa a los navegadores abiertos que algo cambió en un proyecto (un issue
 * se movió, se comentó, se guardó un documento) para que vuelvan a pedir los datos. Solo lleva claves,
 * nunca contenido: lo que cada quien puede ver lo sigue decidiendo la API al recargar. Se alimenta de
 * los eventos públicos de issue y docs; esos módulos no saben que existe.
 */
package ec.kuador.board.live;
