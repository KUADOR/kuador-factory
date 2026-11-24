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

    /**
     * Crea la tarjeta de una rutina en {@code status} (nulo = la primera columna), asignada a {@code assigneeId} (persona o
     * agente; nulo = sin asignar), como si la creara {@code actorId}.
     */
    /**
     * Crea la tarjeta de un correo que llegó a la bandeja del tablero, en {@code status} (nulo = la primera columna), con
     * la etiqueta «correo», asignada a {@code assigneeId} (nulo = la toma el agente de la etapa, si tiene).
     */
    default IssueRef createFromMail(String projectKey, String title, String description, String status, UUID assigneeId, UUID actorId) {
        throw new UnsupportedOperationException("createFromMail");
    }

    default IssueRef createForRoutine(String projectKey, String title, String description, String status, UUID assigneeId, UUID actorId) {
        throw new UnsupportedOperationException("createForRoutine");
    }

    /** Una tarjeta que sugirió el reconocimiento de un proyecto existente y eligió una persona (etiqueta «sugerida»). */
    default IssueRef createSuggested(String projectKey, String title, String description, UUID actorId) {
        throw new UnsupportedOperationException("createSuggested");
    }

    /** El issue está en una columna de terminado (o ya no existe). */
    default boolean isClosed(String issueKey) {
        throw new UnsupportedOperationException("isClosed");
    }

    record CommentView(UUID authorId, String body, Instant createdAt) {}

    /** Todo lo que un agente necesita saber de un issue. Enums como texto. */
    record IssueContext(
            String key, String projectKey, String title, String description, String type, String status, String priority,
            Integer storyPoints, List<String> labels, String parentKey, UUID assigneeId, UUID reporterId, List<CommentView> comments) {}

    Optional<IssueContext> context(String issueKey);

    /**
     * Llena datos propios de la tarjeta (por su nombre o su clave: «Proveedor» o «proveedor»). Los demás se conservan.
     * Un agente solo cambia las tarjetas que tomó. Devuelve los datos como quedaron.
     */
    default java.util.Map<String, String> setCardFields(String issueKey, java.util.Map<String, String> values, UUID actorId) {
        throw new UnsupportedOperationException("setCardFields");
    }

    /** Los datos propios de la tarjeta con el nombre que les puso el tablero («Monto» → «1250.50»), en su orden. */
    default java.util.Map<String, String> cardFields(String issueKey) {
        return java.util.Map.of();
    }

    /** El texto completo de un comentario (el evento trae solo el comienzo). */
    default Optional<String> commentBody(UUID commentId) {
        return Optional.empty();
    }

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

    /** Se puede repartir en tareas: es una épica, o una historia o tarea suelta que al repartirla pasa a ser épica. */
    default boolean canSplit(String issueKey) {
        return false;
    }

    /** Si el issue es una tarea del plan de una épica, su rol y dependencias. */
    Optional<TaskInfo> taskInfo(String issueKey);

    /** Issues que supervisa {@code supervisorId} y esperan a un agente (asignados a uno o por tomar). */
    List<QueueItem> agentQueue(UUID supervisorId);

    /** Issues del tablero sin asignar que esperan a un agente (no incluye puertas): los que puede tomar el siguiente de la cadena. */
    List<QueueItem> openForAgents(String projectKey);

    /** Lo que espera a un agente en el tablero, esté por tomar o ya asignado a un agente (sea quien sea que lo supervise). */
    default List<QueueItem> waitingForAgents(String projectKey) {
        return openForAgents(projectKey);
    }

    /** Persona que supervisa el trabajo de los agentes en el issue, si hay una. */
    Optional<UUID> supervisorOf(String issueKey);

    /** Toma el issue de su etapa como {@code agentId} (reglas de etapa de siempre). */
    Progress takeAs(String issueKey, UUID agentId);

    /** La persona pasa a supervisar el issue: sus agentes lo atienden (p. ej. su runner lo tomó de la bolsa común). */
    default void handOver(String issueKey, UUID personId) {
        throw new UnsupportedOperationException("handOver");
    }

    /** Entrega el issue a la siguiente etapa como {@code agentId}. */
    Progress deliverAs(String issueKey, String comment, UUID agentId);

    /** Entrega el issue a una columna más adelante (p. ej. la revisión de un tablero sin etapas de trabajo). */
    Progress deliverTo(String issueKey, String column, String comment, UUID agentId);

    /**
     * El PR de la tarjeta quedó en conflicto después de fusionar otro: si espera en una puerta, vuelve a donde esa puerta
     * rechaza (para que su autor actualice la rama); si se está trabajando, solo se comenta. No repite el aviso si el
     * último comentario ya lo dice. Devuelve la columna a la que volvió, o vacío si solo se comentó (o nada).
     */
    default Optional<String> returnForConflict(String issueKey, String note, UUID actorId) {
        return Optional.empty();
    }

    /**
     * Si nadie escribe código en la rama de la tarjeta ahora: espera en una puerta o está en una etapa cuyos roles no editan
     * código (p. ej. QA). Entonces el tablero puede actualizar su rama sin pisar a quien la desarrolla.
     */
    default boolean branchIdle(String issueKey) {
        return false;
    }

    /** Devuelve el issue a una columna anterior con el motivo. */
    Progress sendBack(String issueKey, String column, String reason, UUID actorId);

    /**
     * La tarjeta está en una puerta que revisa {@code agentId} (su agente revisor): el modo (AGENT o AGENT_ESCALATE),
     * los nombres de la puerta, de a dónde se devuelve y a dónde avanza, las rutas sensibles, el límite de líneas y quién
     * aprueba si se escala. Vacío si no le toca.
     */
    /** {@code merge}: al aprobar, el tablero fusiona los PR de la tarjeta. */
    record GateReview(String mode, String gateName, String backName, String nextName, List<String> sensitive, Integer maxChangedLines, String approvers,
            boolean merge) {

        public GateReview(String mode, String gateName, String backName, String nextName, List<String> sensitive, Integer maxChangedLines, String approvers) {
            this(mode, gateName, backName, nextName, sensitive, maxChangedLines, approvers, false);
        }

        public boolean escalates() {
            return "AGENT_ESCALATE".equals(mode);
        }
    }

    /**
     * Si la tarjeta está en una puerta que cierra un agente revisor (y existe ese agente): el aviso de «espera tu
     * aprobación» sobra; avisa el revisor con su decisión o, si escala, la asignación a una persona.
     */
    default boolean agentReviews(String issueKey) {
        return false;
    }

    default Optional<GateReview> gateReview(String issueKey, UUID agentId) {
        return Optional.empty();
    }

    /** Si esta persona puede aprobar la puerta en la que está la tarjeta ahora. */
    default boolean canApproveGate(String issueKey, UUID actorId) {
        return false;
    }

    /** Una persona aprueba la puerta en la que está la tarjeta (avanza), con un comentario opcional. */
    default Progress approveGate(String issueKey, String comment, UUID actorId) {
        throw new UnsupportedOperationException();
    }

    /** El agente revisor cierra la puerta: aprueba (avanza) o rechaza con el motivo (vuelve). */
    default Progress reviewAs(String issueKey, boolean approve, String comment, UUID agentId) {
        throw new UnsupportedOperationException();
    }

    /** El agente revisor deja la decisión a una persona aprobadora, con su análisis. */
    default Progress escalate(String issueKey, String analysis, UUID agentId) {
        throw new UnsupportedOperationException();
    }

    /** Una tarjeta del tablero, con lo justo para resumir el avance: columna (nombre y categoría) y épica. */
    record BoardCard(String key, String title, String type, String statusName, String category, String parentKey, Integer storyPoints,
            Instant updatedAt) {}

    /** Todas las tarjetas de un tablero, en el orden de sus columnas. */
    default List<BoardCard> boardCards(String projectKey) {
        throw new UnsupportedOperationException("boardCards");
    }
}
