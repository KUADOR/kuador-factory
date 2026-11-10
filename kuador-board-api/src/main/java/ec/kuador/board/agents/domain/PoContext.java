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
        DocTask docTask, String existingDocContent) {

    public record Doc(String kind, String title, String path, String content) {}

    public record Issue(String key, String title, String description, String type, String status, String priority, Integer storyPoints,
            List<String> labels) {}

    public record Comment(String author, boolean byAgent, String body) {}

    /** {@code substantialOnly}: solo si el issue es sustancial (proporcionalidad de ODD). */
    public record DocTask(String kind, String label, boolean substantialOnly, String instructions) {}
}
