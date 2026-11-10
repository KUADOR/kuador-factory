package ec.kuador.board.issue.domain;

/** Un campo editado y sus valores antes y después, en texto. */
public record FieldChange(String field, String from, String to) {}
