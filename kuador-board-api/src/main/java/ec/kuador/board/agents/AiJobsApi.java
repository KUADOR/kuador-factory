package ec.kuador.board.agents;

import java.util.Optional;
import java.util.UUID;

/** Los trabajos de IA que esperan a la máquina de una persona (ver la tabla ai_job): su runner los toma y los resuelve. */
public interface AiJobsApi {

    /** Lo que el runner necesita para lanzar el cliente de IA: el pedido completo, sin herramientas. */
    /** {@code projectKey}: para reconocer un proyecto, el runner trabaja en la copia de su repo de esa máquina. */
    record JobTask(UUID id, String step, String system, String user, String projectKey) {}

    /** El siguiente trabajo de esa persona, ya tomado por esa máquina; vacío si no hay. */
    Optional<JobTask> claim(UUID workspaceId, UUID memberId, UUID machineId);

    /** El runner devuelve el texto (o el motivo si falló). */
    void complete(UUID workspaceId, UUID memberId, UUID jobId, String text, String error);
}
