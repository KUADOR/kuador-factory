package ec.kuador.board.project;

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
        List<IssueTypeDef> issueTypes, Transitions transitions, List<StageDef> stages, int maxRework) {

    /** Configuración de etapa de una columna (vacía = columna libre: sin roles ni puerta). */
    public Optional<StageDef> stage(String columnKey) {
        return stages.stream().filter(s -> s.column().equals(columnKey)).findFirst();
    }

    /** La siguiente etapa al entregar: la primera columna posterior a la que el flujo deja pasar. */
    public Optional<ColumnDef> nextAfter(String columnKey) {
        int from = indexOf(columnKey);
        return columns.stream().skip(from == Integer.MAX_VALUE ? 0 : from + 1L).filter(c -> canMove(columnKey, c.key())).findFirst();
    }

    public boolean software() {
        return kind == ProjectKind.SOFTWARE;
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
