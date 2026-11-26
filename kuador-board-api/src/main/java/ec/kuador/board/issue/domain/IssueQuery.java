package ec.kuador.board.issue.domain;

import ec.kuador.board.catalog.IssueType;
import java.util.List;
import java.util.UUID;

/**
 * Lo que pide la vista de lista: filtros (un campo nulo o vacío no filtra), orden y página. {@code sort}: {@code key},
 * {@code title}, {@code type}, {@code status}, {@code priority}, {@code created} o {@code updated}. {@code subjectKey}: solo las
 * tarjetas de ese proyecto del tablero.
 */
public record IssueQuery(String text, List<String> statuses, UUID assigneeId, IssueType type, String label, String sort, boolean descending,
        int page, int size, String subjectKey) {

    public IssueQuery(String text, List<String> statuses, UUID assigneeId, IssueType type, String label, String sort, boolean descending,
            int page, int size) {
        this(text, statuses, assigneeId, type, label, sort, descending, page, size, null);
    }

    public static final int MAX_SIZE = 100;

    public IssueQuery {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        text = text == null || text.isBlank() ? null : text.strip();
        label = label == null || label.isBlank() ? null : label.strip();
        subjectKey = subjectKey == null || subjectKey.isBlank() ? null : subjectKey.strip().toUpperCase(java.util.Locale.ROOT);
        sort = sort == null || sort.isBlank() ? "updated" : sort;
        page = Math.max(0, page);
        size = size <= 0 ? 50 : Math.min(size, MAX_SIZE);
    }
}
