package ec.kuador.board.issue.domain;

import ec.kuador.board.catalog.IssueType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Campos editables de un issue. {@code fields}: los datos propios de su tipo (clave → valor); nulo = no se tocan (así
 * quien edita sin conocerlos, como un agente que solo estima, no los borra). {@code subjectKey}: el proyecto en que se
 * trabaja la tarjeta; nulo = el que ya tiene (o, al crearla, el de su épica o el primero del tablero).
 */
public record IssueDetails(
        String title, String description, IssueType type, Priority priority, Integer storyPoints,
        UUID assigneeId, String parentKey, List<String> labels, Map<String, String> fields, String subjectKey) {

    public IssueDetails(String title, String description, IssueType type, Priority priority, Integer storyPoints,
            UUID assigneeId, String parentKey, List<String> labels, Map<String, String> fields) {
        this(title, description, type, priority, storyPoints, assigneeId, parentKey, labels, fields, null);
    }

    public IssueDetails(String title, String description, IssueType type, Priority priority, Integer storyPoints,
            UUID assigneeId, String parentKey, List<String> labels) {
        this(title, description, type, priority, storyPoints, assigneeId, parentKey, labels, null, null);
    }

    public IssueDetails withFields(Map<String, String> clean) {
        return new IssueDetails(title, description, type, priority, storyPoints, assigneeId, parentKey, labels, clean, subjectKey);
    }

    public IssueDetails withSubject(String key) {
        return new IssueDetails(title, description, type, priority, storyPoints, assigneeId, parentKey, labels, fields, key);
    }
}
