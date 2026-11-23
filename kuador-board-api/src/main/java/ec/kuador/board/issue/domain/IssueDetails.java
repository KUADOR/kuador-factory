package ec.kuador.board.issue.domain;

import ec.kuador.board.catalog.IssueType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Campos editables de un issue. {@code fields}: los datos propios de su tipo (clave → valor); nulo = no se tocan (así
 * quien edita sin conocerlos, como un agente que solo estima, no los borra).
 */
public record IssueDetails(
        String title, String description, IssueType type, Priority priority, Integer storyPoints,
        UUID assigneeId, String parentKey, List<String> labels, Map<String, String> fields) {

    public IssueDetails(String title, String description, IssueType type, Priority priority, Integer storyPoints,
            UUID assigneeId, String parentKey, List<String> labels) {
        this(title, description, type, priority, storyPoints, assigneeId, parentKey, labels, null);
    }

    public IssueDetails withFields(Map<String, String> clean) {
        return new IssueDetails(title, description, type, priority, storyPoints, assigneeId, parentKey, labels, clean);
    }
}
