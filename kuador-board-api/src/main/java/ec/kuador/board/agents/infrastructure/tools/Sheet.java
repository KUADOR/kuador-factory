package ec.kuador.board.agents.infrastructure.tools;

import java.util.List;

/**
 * Una planilla leída de un adjunto (hoja de Excel o CSV), lista para cargarse como tabla. Los valores son
 * {@code BigDecimal}, {@code LocalDate}, {@code LocalDateTime}, {@code Boolean}, {@code String} o nulos.
 */
record Sheet(String table, String source, List<Column> columns, List<Object[]> rows, boolean truncated) {

    enum Type {
        NUMBER("DECIMAL(38, 10)"),
        DATE("DATE"),
        TIMESTAMP("TIMESTAMP"),
        BOOLEAN("BOOLEAN"),
        TEXT("VARCHAR");

        final String sql;

        Type(String sql) {
            this.sql = sql;
        }
    }

    record Column(String name, String header, Type type) {}
}
