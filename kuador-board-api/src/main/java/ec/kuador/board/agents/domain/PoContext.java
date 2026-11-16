package ec.kuador.board.agents.domain;

import java.util.List;

/** Todo lo que el agente ve al refinar un issue. Se arma en el código, no lo busca el modelo. */
public record PoContext(
        String agentName, String agentDescription, String roleInstructions,
        String projectKey, String projectName, String projectKind,
        String methodologyName, String methodologySummary, List<String> methodologyRules,
        List<Doc> projectDocs,
        Issue issue, Issue epic,
        List<Comment> comments,
        /** Documento que debe escribir según la metodología (o nulo), con su contenido actual si ya existe. */
        DocTask docTask, String existingDocContent,
        /** Si no está vacía, la épica se reparte en tareas con estos roles («CLAVE: nombre»); vacía = no se reparte. */
        List<String> taskRoles,
        /** Las reglas de agentes del espacio que aplican al refinar (Catálogo → Reglas de agentes). */
        Guide guide) {

    public PoContext {
        taskRoles = taskRoles == null ? List.of() : List.copyOf(taskRoles);
        guide = guide == null ? Guide.NONE : guide;
    }

    public PoContext(String agentName, String agentDescription, String roleInstructions, String projectKey, String projectName, String projectKind,
            String methodologyName, String methodologySummary, List<String> methodologyRules, List<Doc> projectDocs, Issue issue, Issue epic,
            List<Comment> comments, DocTask docTask, String existingDocContent, List<String> taskRoles) {
        this(agentName, agentDescription, roleInstructions, projectKey, projectName, projectKind, methodologyName, methodologySummary, methodologyRules,
                projectDocs, issue, epic, comments, docTask, existingDocContent, taskRoles, Guide.NONE);
    }

    /**
     * {@code rules}: núcleo, reglas comunes, de preguntas e idioma. {@code proposalFields}: cómo llenar cada parte.
     * {@code document} y {@code taskSplit}: cómo escribir el documento y repartir una épica.
     */
    public record Guide(List<String> rules, String proposalFields, String document, String taskSplit) {
        public static final Guide NONE = new Guide(List.of(), "", "", "");
    }

    /** Sin repartir en tareas. */
    public PoContext(String agentName, String agentDescription, String roleInstructions, String projectKey, String projectName, String projectKind,
            String methodologyName, String methodologySummary, List<String> methodologyRules, List<Doc> projectDocs, Issue issue, Issue epic,
            List<Comment> comments, DocTask docTask, String existingDocContent) {
        this(agentName, agentDescription, roleInstructions, projectKey, projectName, projectKind, methodologyName, methodologySummary, methodologyRules,
                projectDocs, issue, epic, comments, docTask, existingDocContent, List.of());
    }

    public boolean splitsIntoTasks() {
        return !taskRoles.isEmpty();
    }

    public record Doc(String kind, String title, String path, String content) {}

    public record Issue(String key, String title, String description, String type, String status, String priority, Integer storyPoints,
            List<String> labels) {}

    public record Comment(String author, boolean byAgent, String body) {}

    /** {@code substantialOnly}: solo si el issue es sustancial (proporcionalidad de ODD). */
    public record DocTask(String kind, String label, boolean substantialOnly, String instructions) {}
}
