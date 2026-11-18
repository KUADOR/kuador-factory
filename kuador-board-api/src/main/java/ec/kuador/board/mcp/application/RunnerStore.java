package ec.kuador.board.mcp.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Máquinas con runner y préstamos de tarjetas (ver RunnerService). */
public interface RunnerStore {

    /** {@code dailyMinutes}: cuánto puede trabajar al día (nulo = sin tope). */
    record Machine(UUID id, UUID workspaceId, UUID memberId, String name, String os, String tool, List<String> roles, int capacity,
            boolean paused, String version, Instant lastSeen, Instant createdAt, Integer dailyMinutes) {

        public Machine(UUID id, UUID workspaceId, UUID memberId, String name, String os, String tool, List<String> roles, int capacity,
                boolean paused, String version, Instant lastSeen, Instant createdAt) {
            this(id, workspaceId, memberId, name, os, tool, roles, capacity, paused, version, lastSeen, createdAt, null);
        }
    }

    /** {@code runTokenId}: la credencial de esta ejecución (se revoca al cerrar el préstamo). */
    record Lease(UUID id, UUID workspaceId, String issueKey, String epicKey, String status, UUID machineId, UUID memberId, UUID agentId,
            Instant startedAt, Instant expiresAt, Instant releasedAt, String outcome, UUID runTokenId) {}

    // ---- Máquinas ----

    void saveMachine(Machine machine);

    Optional<Machine> machine(UUID id);

    List<Machine> machines(UUID workspaceId);

    void deleteMachine(UUID id);

    void setDailyMinutes(UUID id, Integer minutes);

    /** Minutos que trabajó la máquina desde {@code since} (préstamos cerrados y los que siguen abiertos). */
    long minutesSince(UUID machineId, Instant since, Instant now);

    // ---- Préstamos ----

    /** Crea el préstamo si nadie más tiene la tarjeta; falso si otra máquina la ganó. */
    boolean insertLease(Lease lease);

    void setRunToken(UUID leaseId, UUID tokenId);

    List<Lease> activeLeases(UUID workspaceId);

    Optional<Lease> activeLease(UUID workspaceId, String issueKey);

    /** Renueva el préstamo de esa máquina; falso si ya no es suyo (venció o lo liberaron). */
    boolean renew(UUID workspaceId, String issueKey, UUID machineId, Instant expiresAt);

    /** Cierra el préstamo de esa máquina con su resultado. */
    Optional<Lease> release(UUID workspaceId, String issueKey, UUID machineId, String outcome, Instant now);

    /** Préstamos vencidos de todos los espacios (las máquinas que dejaron de renovar). */
    List<Lease> expired(Instant now);

    /** Cuántas veces se cortó o falló la tarjeta desde {@code since}. */
    int failures(UUID workspaceId, String issueKey, Instant since);

    /** Quién tomó por última vez una tarea de la épica desde {@code since}. */
    Optional<UUID> lastEpicPerson(UUID workspaceId, String epicKey, Instant since);

    void deleteLeasesBefore(Instant before);
}
