/**
 * Agentes que trabajan en el tablero. Escuchan los eventos de issue (p. ej. una persona asigna un
 * issue al Agente PO), arman su contexto (metodología, documentos del proyecto, issue, épica,
 * comentarios), llaman al modelo y actúan como un miembro más: comentan, escriben documentos y
 * devuelven el issue a una persona para que apruebe. Cada ejecución queda registrada.
 */
package ec.kuador.board.agents;
