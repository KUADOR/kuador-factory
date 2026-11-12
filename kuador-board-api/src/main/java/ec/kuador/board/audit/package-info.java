/**
 * Auditoría del espacio y de la plataforma: quién hizo qué, cuándo, desde qué IP y con qué resultado (incluidos los
 * intentos fallidos y los accesos denegados). Se alimenta de un filtro HTTP y de las pistas que dejan los demás módulos
 * en la petición ({@code shared.web.AuditHints}); nadie depende de este módulo. Solo agrega filas.
 */
package ec.kuador.board.audit;
