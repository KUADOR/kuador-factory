package ec.kuador.board.issue.application;

import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.issue.IssueApi.BoardCard;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.issue.domain.IssueFilter;
import ec.kuador.board.project.ProjectView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Leer un tablero: completo, la vista con el tope de terminadas, la lista paginada y el resumen de sus tarjetas. */
final class BoardReader extends IssueSupport {

    BoardReader(Ports ports) {
        super(ports);
    }

    List<Issue> board(String projectKey, IssueFilter filter) {
        ProjectView project = project(projectKey);
        return issues.findByProject(projectKey, filter == null ? IssueFilter.none() : filter).stream()
                .sorted(Comparator.comparingInt((Issue i) -> project.indexOf(i.status())).thenComparingInt(Issue::position))
                .toList();
    }

    /** El tablero: todo el trabajo en curso y, de cada columna de cierre, solo las últimas {@link IssueService#DONE_VISIBLE}. */
    IssueService.BoardView boardView(String projectKey) {
        ProjectView project = project(projectKey);
        List<String> done = project.columns().stream().filter(c -> c.category() == ec.kuador.board.catalog.ColumnCategory.DONE)
                .map(ec.kuador.board.catalog.ColumnDef::key).toList();
        List<Issue> shown = new ArrayList<>(issues.findOutside(projectKey, done));
        java.util.Map<String, Long> counts = issues.countByStatus(projectKey);
        java.util.Map<String, Long> totals = new java.util.LinkedHashMap<>();
        for (String column : done) {
            shown.addAll(issues.findLatestInColumn(projectKey, column, IssueService.DONE_VISIBLE));
            totals.put(column, counts.getOrDefault(column, 0L));
        }
        java.util.Set<String> present = shown.stream().map(Issue::key).collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> missingParents = shown.stream().map(Issue::parentKey).filter(k -> k != null && !present.contains(k))
                .collect(java.util.stream.Collectors.toSet());
        shown.sort(Comparator.comparingInt((Issue i) -> project.indexOf(i.status())).thenComparingInt(Issue::position));
        return new IssueService.BoardView(shown, totals, issues.epicProgress(projectKey, done), issues.titles(missingParents), IssueService.DONE_VISIBLE);
    }

    /** La vista de lista: todas las tarjetas del tablero, también las terminadas, con filtros, orden y página. */
    ec.kuador.board.issue.domain.IssuePage search(String projectKey, ec.kuador.board.issue.domain.IssueQuery query) {
        project(projectKey);
        return issues.search(projectKey, query);
    }

    List<BoardCard> boardCards(String projectKey) {
        ProjectView project = project(projectKey);
        List<BoardCard> out = new java.util.ArrayList<>();
        for (ColumnDef c : project.columns()) {
            for (Issue i : issues.findColumn(project.key(), c.key())) {
                out.add(new BoardCard(i.key(), i.title(), i.type().name(), c.name(), c.category().name(), i.parentKey(), i.storyPoints(), i.updatedAt()));
            }
        }
        return out;
    }
}
