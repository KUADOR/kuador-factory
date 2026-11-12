package ec.kuador.board.catalog;

import java.util.List;
import java.util.UUID;

/**
 * Plantilla de flujo: lo que recibe un proyecto nuevo (columnas, tipos de issue y etapas). {@code agents}: roles del
 * equipo de agentes que el tablero trae por defecto (si está vacía, los de sus etapas).
 */
public record WorkflowTemplate(
        UUID id, String key, String name, String description, ProjectKind kind, List<ColumnDef> columns,
        List<IssueTypeDef> issueTypes, List<StageDef> stages, List<TransitionDef> transitions, List<String> agents, boolean builtIn, long version) {

    /** Plantillas guardadas antes de las etapas o del equipo no los traen: quedan vacíos. */
    public WorkflowTemplate {
        stages = stages == null ? List.of() : List.copyOf(stages);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        agents = agents == null ? List.of() : List.copyOf(agents);
    }

    /** Roles del equipo por defecto: los declarados o, si no hay, los que cubren sus etapas. */
    public List<String> teamRoles() {
        if (!agents.isEmpty()) return agents;
        return stages.stream().flatMap(s -> s.roles().stream()).distinct().toList();
    }
}
