package ec.kuador.board.issue.domain;

import ec.kuador.board.catalog.IssueType;

import ec.kuador.board.shared.error.DomainException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Agregado Issue. Valida sus propias reglas; no sabe nada de HTTP ni de base de datos. */
public final class Issue {

    private static final int MAX_LABELS = 10;

    private final UUID id;
    private final String key;
    private final String projectKey;
    private String title;
    private String description;
    private IssueType type;
    /** Clave de la columna del proyecto donde está el issue. */
    private String status;
    private Priority priority;
    private Integer storyPoints;
    private UUID assigneeId;
    private final UUID reporterId;
    private String parentKey;
    private List<String> labels;
    private int position;
    /** Veces que el issue volvió a una etapa anterior (freno de bucles). */
    private int reworkCount;
    /** Persona que puso a los agentes a trabajar en el issue: solo sus agentes lo atienden. */
    private UUID supervisorId;
    /** Tarea de una épica: rol que la trabaja mientras la épica está en desarrollo (p. ej. BACKEND). */
    private String workRole;
    /** Tarea de una épica: claves de las tareas que deben terminar antes de empezar esta. */
    private List<String> dependsOn = List.of();
    /** Quién revisa esta tarjeta en las puertas: nulo = lo del tablero; PERSON = siempre una persona; AGENT = el agente revisor. */
    private String reviewBy;
    /** Datos propios del tipo de tarjeta (clave del dato → valor como texto), ya validados contra el tablero. */
    private java.util.Map<String, String> fields = java.util.Map.of();
    private final Instant createdAt;
    private Instant updatedAt;

    public Issue(
            UUID id, String key, String projectKey, String title, String description, IssueType type,
            String status, Priority priority, Integer storyPoints, UUID assigneeId, UUID reporterId,
            String parentKey, List<String> labels, int position, Instant createdAt, Instant updatedAt) {
        this(id, key, projectKey, title, description, type, status, priority, storyPoints, assigneeId, reporterId, parentKey, labels,
                position, 0, createdAt, updatedAt);
    }

    public Issue(
            UUID id, String key, String projectKey, String title, String description, IssueType type,
            String status, Priority priority, Integer storyPoints, UUID assigneeId, UUID reporterId,
            String parentKey, List<String> labels, int position, int reworkCount, Instant createdAt, Instant updatedAt) {
        this.reworkCount = reworkCount;
        this.id = id;
        this.key = key;
        this.projectKey = projectKey;
        this.reporterId = reporterId;
        this.createdAt = createdAt;
        if (status == null || status.isBlank()) {
            throw new DomainException("El issue necesita un paso.");
        }
        this.status = status;
        this.position = position;
        apply(title, description, type, priority, storyPoints, assigneeId, parentKey, labels);
        this.updatedAt = updatedAt;
    }

    public static Issue create(
            String projectKey, int number, IssueDetails d, UUID reporterId, String status, int position, Instant now) {
        Issue issue = new Issue(
                UUID.randomUUID(), projectKey + "-" + number, projectKey, d.title(), d.description(), d.type(),
                status, d.priority(), d.storyPoints(), d.assigneeId(), reporterId, d.parentKey(), d.labels(),
                position, now, now);
        if (d.fields() != null) issue.fields(d.fields());
        return issue;
    }

    /**
     * Reemplaza todos los campos editables (semántica PUT) y devuelve qué cambió, sin incluir al
     * responsable (eso lo informa {@link #assigneeId()} antes y después). Si nada cambió, no toca updatedAt.
     */
    public List<FieldChange> edit(IssueDetails d, Instant now) {
        String oldTitle = title;
        String oldDescription = description;
        IssueType oldType = type;
        Priority oldPriority = priority;
        Integer oldPoints = storyPoints;
        UUID oldAssignee = assigneeId;
        String oldParent = parentKey;
        List<String> oldLabels = labels;

        apply(d.title(), d.description(), d.type(), d.priority(), d.storyPoints(), d.assigneeId(), d.parentKey(), d.labels());

        List<FieldChange> changes = new ArrayList<>();
        diff(changes, "title", oldTitle, title);
        if (!Objects.equals(blankToNull(oldDescription), blankToNull(description))) {
            changes.add(new FieldChange("description", null, null));
        }
        diff(changes, "type", oldType, type);
        diff(changes, "priority", oldPriority, priority);
        diff(changes, "storyPoints", oldPoints, storyPoints);
        diff(changes, "parent", oldParent, parentKey);
        if (!oldLabels.equals(labels)) {
            changes.add(new FieldChange("labels", String.join(", ", oldLabels), String.join(", ", labels)));
        }
        if (d.fields() != null && !fields.equals(d.fields())) {
            java.util.Map<String, String> before = fields;
            fields(d.fields());
            // Qué datos cambiaron (los valores pueden ser de un cliente: no viajan en el evento).
            java.util.Set<String> keys = new java.util.TreeSet<>(before.keySet());
            keys.addAll(fields.keySet());
            keys.removeIf(k -> Objects.equals(before.get(k), fields.get(k)));
            changes.add(new FieldChange("fields", null, String.join(", ", keys)));
        }
        if (!changes.isEmpty() || !Objects.equals(oldAssignee, assigneeId)) {
            this.updatedAt = now;
        }
        return changes;
    }

    private static void diff(List<FieldChange> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.add(new FieldChange(field, before == null ? null : before.toString(), after == null ? null : after.toString()));
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public void moveTo(String target, Instant now) {
        if (target == null || target.isBlank()) {
            throw new DomainException("Indica el paso de destino.");
        }
        if (!target.equals(status)) {
            this.status = target;
            this.updatedAt = now;
        }
    }

    public void assign(UUID memberId, Instant now) {
        this.assigneeId = memberId;
        this.updatedAt = now;
    }

    public void reposition(int position) {
        this.position = position;
    }

    /** Registra una vuelta atrás y devuelve el total. */
    public int countRework() {
        return ++reworkCount;
    }

    public int reworkCount() {
        return reworkCount;
    }

    private void apply(
            String title, String description, IssueType type, Priority priority, Integer storyPoints,
            UUID assigneeId, String parentKey, List<String> labels) {
        if (title == null || title.isBlank() || title.length() > 255) {
            throw new DomainException("El título es obligatorio y tiene máximo 255 caracteres.");
        }
        if (type == null) {
            throw new DomainException("Indica el tipo: EPIC, STORY, TASK o BUG.");
        }
        if (storyPoints != null && (storyPoints < 0 || storyPoints > 100)) {
            throw new DomainException("Los story points van de 0 a 100.");
        }
        if (parentKey != null && parentKey.equals(key)) {
            throw new DomainException("Un issue no puede ser su propio padre.");
        }
        if (type == IssueType.EPIC && parentKey != null) {
            throw new DomainException("Una épica no puede tener padre.");
        }
        List<String> cleanLabels = labels == null ? List.of()
                : labels.stream().map(String::strip).filter(l -> !l.isEmpty()).map(String::toLowerCase).distinct().toList();
        if (cleanLabels.size() > MAX_LABELS) {
            throw new DomainException("Máximo " + MAX_LABELS + " etiquetas por issue.");
        }
        this.title = title.strip();
        this.description = description;
        this.type = type;
        this.priority = priority == null ? Priority.MEDIUM : priority;
        this.storyPoints = storyPoints;
        this.assigneeId = assigneeId;
        this.parentKey = parentKey;
        this.labels = cleanLabels;
    }

    public UUID id() { return id; }
    public String key() { return key; }
    public String projectKey() { return projectKey; }
    public String title() { return title; }
    public String description() { return description; }
    public IssueType type() { return type; }
    public String status() { return status; }
    public Priority priority() { return priority; }
    public Integer storyPoints() { return storyPoints; }
    public UUID assigneeId() { return assigneeId; }
    public UUID reporterId() { return reporterId; }
    public String parentKey() { return parentKey; }
    public List<String> labels() { return labels; }
    public int position() { return position; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    public UUID supervisorId() {
        return supervisorId;
    }

    public String workRole() {
        return workRole;
    }

    public List<String> dependsOn() {
        return dependsOn;
    }

    /** Lo marca como tarea de una épica: quién la trabaja y de qué depende. */
    public void planAsTask(String role, List<String> dependencies) {
        if (parentKey == null) throw new DomainException("Una tarea del plan pertenece a una épica.");
        this.workRole = role == null || role.isBlank() ? null : role.strip().toUpperCase(java.util.Locale.ROOT);
        this.dependsOn = dependencies == null ? List.of() : dependencies.stream().filter(d -> !d.equals(key)).distinct().toList();
    }

    public String reviewBy() {
        return reviewBy;
    }

    public java.util.Map<String, String> fields() {
        return fields;
    }

    /** Los datos propios de la tarjeta, ya validados contra las definiciones de su tablero. */
    public void fields(java.util.Map<String, String> values) {
        this.fields = values == null ? java.util.Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
    }

    /** Pide que las puertas de esta tarjeta las revise una persona ({@code PERSON}), el agente ({@code AGENT}) o lo del tablero (nulo). */
    public void reviewBy(String who) {
        String v = who == null || who.isBlank() ? null : who.strip().toUpperCase(java.util.Locale.ROOT);
        if (v != null && !v.equals("PERSON") && !v.equals("AGENT")) throw new DomainException("La revisión es PERSON, AGENT o vacía (lo del tablero).");
        this.reviewBy = v;
    }

    /** Quién supervisa el trabajo de los agentes en este issue (nulo = nadie). */
    public void superviseBy(UUID personId) {
        this.supervisorId = personId;
    }
}
