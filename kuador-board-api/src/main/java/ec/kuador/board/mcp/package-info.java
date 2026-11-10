/**
 * Servidor MCP del tablero. Claude Code se conecta con la conexión (token RUNNER) de una persona y
 * trabaja como los agentes del espacio: lee la cola, toma, comenta, escribe documentos y entrega.
 * Solo atiende los issues que esa persona supervisa, así cada suscripción sirve solo a su dueño.
 * También genera el kit para el repositorio: {@code .mcp.json}, subagentes y el comando /pendientes.
 */
package ec.kuador.board.mcp;
