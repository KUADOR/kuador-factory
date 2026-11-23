/**
 * Conexiones del negocio: los sistemas de la empresa (correo, agenda, ERP, CRM) que los agentes pueden usar. Cada
 * conexión es un servidor MCP por HTTP con su credencial cifrada; el tablero hace de intermediario, así la credencial
 * nunca sale del servidor, vale con cualquier proveedor de IA y cada llamada pasa por las mismas reglas: una
 * herramienta que solo lee se puede usar; una que escribe, solo si quien administra la habilitó.
 */
package ec.kuador.board.connections;
