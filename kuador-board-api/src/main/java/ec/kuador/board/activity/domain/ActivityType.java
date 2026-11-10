package ec.kuador.board.activity.domain;

public enum ActivityType {
    CREATED,
    UPDATED,
    MOVED,
    ASSIGNED,
    COMMENTED,
    DELETED,
    /** Se guardó una versión de un documento: field = tipo, from = id del documento, to = versión. */
    DOCUMENT
}
