package ec.kuador.board.issue.domain;

import ec.kuador.board.catalog.IssueType;

import java.util.UUID;

/** Filtros opcionales del tablero; un campo nulo no filtra. */
public record IssueFilter(String text, UUID assigneeId, IssueType type) {

    public static IssueFilter none() {
        return new IssueFilter(null, null, null);
    }
}
