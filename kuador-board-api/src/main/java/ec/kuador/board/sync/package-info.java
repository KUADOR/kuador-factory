/**
 * Sincronización de documentos con el repositorio del proyecto. Hexagonal: el puerto
 * {@code RepositoryFiles} lo implementa hoy una carpeta local; GitHub será otro adaptador.
 * Sincroniza en tres vías (tablero, archivo y última sincronización) para no pisar cambios.
 */
package ec.kuador.board.sync;
