/**
 * Agentes que trabajan en el tablero. Escuchan los eventos de issue (p. ej. una persona asigna un
 * issue al Agente PO), arman su contexto (metodología, documentos del proyecto, issue, épica,
 * comentarios), llaman al modelo y actúan como un miembro más: comentan (breve, enlazando el documento),
 * escriben documentos y pasan el issue a la siguiente etapa, donde lo toma el agente que la cubre; en las
 * puertas y ante preguntas vuelve a una persona. Cada ejecución queda registrada.
 */
package ec.kuador.board.agents;
