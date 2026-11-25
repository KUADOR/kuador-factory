package ec.kuador.board.issue.domain;

import java.util.List;

/** Una página de la lista de tarjetas y cuántas hay en total con esos filtros. */
public record IssuePage(List<Issue> items, long total, int page, int size) {}
