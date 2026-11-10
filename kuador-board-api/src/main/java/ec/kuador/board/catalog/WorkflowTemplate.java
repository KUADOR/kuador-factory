package ec.kuador.board.catalog;

import java.util.List;
import java.util.UUID;

/** Plantilla de flujo: lo que recibe un proyecto nuevo (columnas y tipos de issue). */
public record WorkflowTemplate(
        UUID id, String key, String name, String description, ProjectKind kind, List<ColumnDef> columns,
        List<IssueTypeDef> issueTypes, List<StageDef> stages, List<TransitionDef> transitions, boolean builtIn, long version) {

    /** Plantillas guardadas antes de las etapas no las traen: quedan vacías (flujo libre, sin roles). */
    public WorkflowTemplate {
        stages = stages == null ? List.of() : List.copyOf(stages);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
    }
}
