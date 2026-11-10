package ec.kuador.board.issue.domain;

import ec.kuador.board.catalog.IssueType;

import java.util.List;
import java.util.UUID;

/** Campos editables de un issue. */
public record IssueDetails(
        String title, String description, IssueType type, Priority priority, Integer storyPoints,
        UUID assigneeId, String parentKey, List<String> labels) {}
