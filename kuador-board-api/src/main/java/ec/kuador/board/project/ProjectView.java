package ec.kuador.board.project;

import ec.kuador.board.catalog.Capability;
import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.catalog.IssueTypeDef;
import ec.kuador.board.catalog.ProjectKind;
import ec.kuador.board.catalog.StageDef;
import java.util.List;
import java.util.Optional;

/**
 * Vista pública de un proyecto: su tipo, plantilla de origen, metodología (o nula), flujo en orden y
 * tipos de issue que usa.
 */
public record ProjectView(
        String key, String name, ProjectKind kind, String templateKey, String methodology, List<ColumnDef> columns,
        List<IssueTypeDef> issueTypes, Transitions transitions, List<StageDef> stages, int maxRework, List<Capability> capabilities,
        ec.kuador.board.catalog.EstimateUnit estimate) {

    public ProjectView {
        estimate = estimate == null ? ec.kuador.board.catalog.EstimateUnit.POINTS : estimate;
        capabilities = capabilities == null ? Capability.defaultsFor(kind) : Capability.normalize(capabilities);
        kind = Capability.kindOf(capabilities);
    }

    /** Tablero sin capacidades declaradas: valen las de su tipo. */
    public ProjectView(String key, String name, ProjectKind kind, String templateKey, String methodology, List<ColumnDef> columns,
            List<IssueTypeDef> issueTypes, Transitions transitions, List<StageDef> stages, int maxRework) {
        this(key, name, kind, templateKey, methodology, columns, issueTypes, transitions, stages, maxRework, null, null);
    }

    public ProjectView(String key, String name, ProjectKind kind, String templateKey, String methodology, List<ColumnDef> columns,
            List<IssueTypeDef> issueTypes, Transitions transitions, List<StageDef> stages, int maxRework, List<Capability> capabilities) {
        this(key, name, kind, templateKey, methodology, columns, issueTypes, transitions, stages, maxRework, capabilities, null);
    }

    /** Los datos propios del tipo de tarjeta (vacío si no tiene). */
    public List<ec.kuador.board.catalog.FieldDef> fieldsOf(IssueType type) {
        return issueTypes.stream().filter(t -> t.type() == type).findFirst().map(IssueTypeDef::fields).orElse(List.of());
    }

    /** Si el tablero estima el tamaño de sus tarjetas. */
    public boolean estimates() {
        return estimate != ec.kuador.board.catalog.EstimateUnit.NONE;
    }

    public boolean can(Capability capability) {
        return capabilities.contains(capability);
    }

    /** Lleva manual de trabajo y documentos con versiones. */
    public boolean documents() {
        return can(Capability.DOCUMENTS);
    }

    /** Trabaja con un repositorio de código: piezas, ramas, Pull Requests y CI. */
    public boolean repository() {
        return can(Capability.REPOSITORY);
    }

    /** Reparte una tarjeta grande en tareas. */
    public boolean splits() {
        return can(Capability.SPLIT);
    }

    /** Configuración de etapa de una columna (vacía = columna libre: sin roles ni puerta). */
    public Optional<StageDef> stage(String columnKey) {
        return stages.stream().filter(s -> s.column().equals(columnKey)).findFirst();
    }

    /** La siguiente etapa al entregar: la primera columna posterior a la que el flujo deja pasar. */
    public Optional<ColumnDef> nextAfter(String columnKey) {
        int from = indexOf(columnKey);
        return columns.stream().skip(from == Integer.MAX_VALUE ? 0 : from + 1L).filter(c -> canMove(columnKey, c.key())).findFirst();
    }

    /**
     * Si una épica en {@code columnKey} se reparte en tareas al entregarla: el tablero reparte y la etapa
     * siguiente es de trabajo (con roles y sin puerta), p. ej. de Diseño a Desarrollo. De Refinamiento a Aprobación no.
     */
    public boolean epicSplitsInto(String columnKey) {
        return splits() && nextAfter(columnKey).flatMap(n -> stage(n.key())).map(st -> !st.gate() && !st.roles().isEmpty()).orElse(false);
    }

    public Optional<ColumnDef> column(String columnKey) {
        return columns.stream().filter(c -> c.key().equals(columnKey)).findFirst();
    }

    public int indexOf(String columnKey) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).key().equals(columnKey)) return i;
        }
        return Integer.MAX_VALUE;
    }

    /** Si el flujo permite mover un issue de una columna a otra. */
    public boolean canMove(String from, String to) {
        return transitions.allows(from, to);
    }

    public boolean allows(IssueType type) {
        return issueTypes.stream().anyMatch(t -> t.type() == type);
    }
}
