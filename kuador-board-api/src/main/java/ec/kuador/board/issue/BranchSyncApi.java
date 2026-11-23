package ec.kuador.board.issue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * El PR de una tarjeta quedó en conflicto con su rama destino mientras la tarjeta esperaba en una puerta o en una etapa
 * que no escribe código (QA). En vez de devolverla a Desarrollo (y repetir QA y revisión), la tarjeta se queda donde está
 * y el runner de quien la desarrolla hace una tarea corta: traer la rama destino, resolver, probar y subir. Si no se
 * puede, recién entonces vuelve a Desarrollo.
 */
public interface BranchSyncApi {

    /** Un pedido de actualizar rama. {@code role}: el rol que programa la tarjeta (el que la resuelve en su runner). */
    record BranchSync(String issueKey, String projectKey, String repository, int number, String branch, String baseBranch, String role,
            String note, Instant requestedAt, int attempts) {}

    /** El conflicto que vio el tablero: el PR y su rama destino. */
    record Conflict(String repository, int number, String branch, String baseBranch) {}

    /**
     * Pide actualizar la rama si la tarjeta espera en una puerta o en una etapa que no escribe código y el tablero tiene un rol
     * que programa. Comenta en la tarjeta. Falso si no corresponde (entonces sigue la devolución de siempre).
     */
    boolean request(String issueKey, Conflict conflict, String note, UUID actorId);

    /** El pedido pendiente de la tarjeta, si hay. */
    Optional<BranchSync> pending(String issueKey);

    /** Los pedidos pendientes del espacio actual, los más viejos primero. */
    List<BranchSync> pending();

    /** El runner lo resolvió: se borra el pedido y se avisa ({@link BranchSynced}) para que siga la revisión. */
    void resolved(String issueKey, String detail, UUID actorId);

    /**
     * El runner no pudo: suma un intento. Al llegar al tope (o si {@code giveUp}), se borra el pedido y la tarjeta vuelve a
     * Desarrollo como antes, con el motivo.
     */
    void failed(String issueKey, String reason, boolean giveUp, UUID actorId);
}
