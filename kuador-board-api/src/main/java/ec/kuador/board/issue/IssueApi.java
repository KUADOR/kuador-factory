package ec.kuador.board.issue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** API pública del módulo para el resto de la aplicación. */
public interface IssueApi {

    /** Datos mínimos de un issue: clave, proyecto, número y título. */
    record IssueRef(String key, String projectKey, int number, String title) {}

    Optional<IssueRef> find(String issueKey);

    /** Crea un issue en la primera columna del proyecto (p. ej. al importar una spec del repo). */
    IssueRef createForImport(String projectKey, String title, UUID actorId);

    record CommentView(UUID authorId, String body, Instant createdAt) {}

    /** Todo lo que un agente necesita saber de un issue. Enums como texto. */
    record IssueContext(
            String key, String projectKey, String title, String description, String type, String status, String priority,
            Integer storyPoints, List<String> labels, String parentKey, UUID assigneeId, UUID reporterId, List<CommentView> comments) {}

    Optional<IssueContext> context(String issueKey);

    /** Comenta como {@code authorId} (persona o agente); queda en el historial. */
    void addComment(String issueKey, UUID authorId, String body);

    void reassign(String issueKey, UUID memberId, UUID actorId);

    /** Pone los puntos solo si el issue no tenía: nunca pisa una estimación humana. */
    boolean setStoryPointsIfEmpty(String issueKey, int points, UUID actorId);

    // ---- Trabajo de los agentes (servidor MCP) ----

    /** Un issue que espera a los agentes de una persona. {@code agentRoles}: roles de agente que lo cubren. */
    record QueueItem(
            String key, String projectKey, String title, String type, String priority, String status, String statusName, boolean gate,
            List<String> agentRoles, UUID assigneeId, String assigneeName, int reworkCount) {}

    /** Dónde quedó un issue después de una acción. */
    record Progress(String key, String status, String statusName, UUID assigneeId) {}

    /**
     * Una tarea del plan de una épica. {@code role}: rol que la trabaja (p. ej. BACKEND); {@code dependsOn}: números
     * (desde 1) de otras tareas de la misma lista que deben terminar antes.
     */
    record PlannedTask(String title, String description, String role, List<Integer> dependsOn) {}

    /**
     * Crea una tarjeta por tarea, hijas de la épica, en la primera columna: arrancan cuando la épica llega a una
     * etapa de trabajo. Si la épica ya tenía tareas que no arrancaron, se reemplazan (p. ej. al rehacer el plan).
     */
    List<IssueRef> createEpicTasks(String epicKey, List<PlannedTask> tasks, UUID actorId);

    /** Tarea de una épica vista desde fuera: rol, dependencias y cuáles siguen sin terminar. */
    record TaskInfo(String key, String title, String status, String statusName, String workRole, List<String> dependsOn,
            List<String> pendingDependencies, String assigneeName) {}

    /** Tareas de una épica (vacío si no tiene). */
    List<TaskInfo> epicTasks(String epicKey);

    /** Si el issue es una tarea del plan de una épica, su rol y dependencias. */
    Optional<TaskInfo> taskInfo(String issueKey);

    /** Issues que supervisa {@code supervisorId} y esperan a un agente (asignados a uno o por tomar). */
    List<QueueItem> agentQueue(UUID supervisorId);

    /** Issues del tablero sin asignar que esperan a un agente (no incluye puertas): los que puede tomar el siguiente de la cadena. */
    List<QueueItem> openForAgents(String projectKey);

    /** Persona que supervisa el trabajo de los agentes en el issue, si hay una. */
    Optional<UUID> supervisorOf(String issueKey);

    /** Toma el issue de su etapa como {@code agentId} (reglas de etapa de siempre). */
    Progress takeAs(String issueKey, UUID agentId);

    /** Entrega el issue a la siguiente etapa como {@code agentId}. */
    Progress deliverAs(String issueKey, String comment, UUID agentId);

    /** Entrega el issue a una columna más adelante (p. ej. la revisión de un tablero sin etapas de trabajo). */
    Progress deliverTo(String issueKey, String column, String comment, UUID agentId);

    /** Devuelve el issue a una columna anterior con el motivo. */
    Progress sendBack(String issueKey, String column, String reason, UUID actorId);
}
