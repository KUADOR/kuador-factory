/**
 * Acceso por tablero: quien edita o solo mira ve únicamente los tableros donde está. Fija {@code BoardScope} en cada
 * petición y rechaza con 404 las rutas de un tablero, issue, documento o adjunto que no puede ver. Dueños,
 * administradores y agentes ven todos.
 */
package ec.kuador.board.boardaccess;
