/**
 * Servidor MCP del tablero (estándar: sirve cualquier cliente de IA con MCP). Se entra con la conexión
 * de agentes locales (token RUNNER) de una persona y
 * trabaja como los agentes del espacio: lee la cola, toma, comenta, escribe documentos y entrega.
 * Solo atiende los issues que esa persona supervisa, así cada suscripción sirve solo a su dueño.
 * También genera el kit para el repositorio en el formato de cada cliente (Claude Code, Codex u otro).
 */
package ec.kuador.board.mcp;
