package ec.kuador.board.catalog;

import java.util.List;
import java.util.UUID;

/**
 * Plantilla de flujo: lo que recibe un tablero nuevo (columnas, tipos de tarjeta y etapas). {@code agents}: roles del
 * equipo de agentes que el tablero trae por defecto (si está vacía, los de sus etapas). {@code capabilities}: lo que el
 * tablero sabe hacer además del flujo (documentos, repositorio de código, reparto); {@code kind} es el dato anterior a
 * las capacidades y solo se usa cuando una plantilla guardada no las trae.
 */
public record WorkflowTemplate(
        UUID id, String key, String name, String description, ProjectKind kind, List<ColumnDef> columns,
        List<IssueTypeDef> issueTypes, List<StageDef> stages, List<TransitionDef> transitions, List<String> agents, List<Capability> capabilities,
        boolean builtIn, long version) {

    /** Plantillas guardadas antes de las etapas o del equipo no los traen: quedan vacíos. */
    public WorkflowTemplate {
        stages = stages == null ? List.of() : List.copyOf(stages);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        agents = agents == null ? List.of() : List.copyOf(agents);
        // Sin capacidades declaradas valen las de su tipo; sin tipo, el que corresponde a sus capacidades.
        if (capabilities == null) capabilities = kind == null ? null : Capability.defaultsFor(kind);
        else capabilities = Capability.normalize(capabilities);
        if (capabilities != null) kind = Capability.kindOf(capabilities);
    }

    /** Plantilla sin capacidades declaradas: valen las de su tipo. */
    public WorkflowTemplate(UUID id, String key, String name, String description, ProjectKind kind, List<ColumnDef> columns,
            List<IssueTypeDef> issueTypes, List<StageDef> stages, List<TransitionDef> transitions, List<String> agents, boolean builtIn, long version) {
        this(id, key, name, description, kind, columns, issueTypes, stages, transitions, agents, null, builtIn, version);
    }

    public boolean can(Capability capability) {
        return capabilities != null && capabilities.contains(capability);
    }

    /** Roles del equipo por defecto: los declarados o, si no hay, los que cubren sus etapas. */
    public List<String> teamRoles() {
        if (!agents.isEmpty()) return agents;
        return stages.stream().flatMap(s -> s.roles().stream()).distinct().toList();
    }
}
