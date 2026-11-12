package ec.kuador.board.agents.infrastructure.tools;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Las planillas de un trabajo como tablas en una base en memoria (H2), para que el agente obtenga cifras exactas con
 * consultas. Vive lo que dura el trabajo. Las consultas corren con un usuario que solo puede leer las tablas: sin
 * permisos de administración no hay acceso a archivos ni a código del servidor.
 */
final class DataRoom implements AutoCloseable {

    static final int MAX_RESULT_ROWS = 500;
    private static final Pattern READ_ONLY = Pattern.compile("(?is)^\\s*(select|with|values|table)\\b.*");

    record Result(List<String> columns, List<List<Object>> rows, boolean truncated) {}

    private final Connection admin;
    private final Connection reader;

    DataRoom(List<Sheet> sheets) {
        String url = "jdbc:h2:mem:sala_" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString();
        try {
            admin = DriverManager.getConnection(url, "sala", password);
            try (Statement st = admin.createStatement()) {
                for (Sheet s : sheets) {
                    StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(quote(s.table())).append(" (");
                    for (int i = 0; i < s.columns().size(); i++) {
                        if (i > 0) ddl.append(", ");
                        ddl.append(quote(s.columns().get(i).name())).append(' ').append(s.columns().get(i).type().sql);
                    }
                    st.execute(ddl.append(')').toString());
                    load(s);
                }
                st.execute("CREATE USER LECTOR PASSWORD '" + password + "'");
                st.execute("GRANT SELECT ON SCHEMA PUBLIC TO LECTOR");
            }
            reader = DriverManager.getConnection(url, "LECTOR", password);
            reader.setReadOnly(true);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudieron cargar las planillas: " + e.getMessage(), e);
        }
    }

    private void load(Sheet s) throws SQLException {
        String marks = String.join(", ", s.columns().stream().map(c -> "?").toList());
        try (PreparedStatement ps = admin.prepareStatement("INSERT INTO " + quote(s.table()) + " VALUES (" + marks + ")")) {
            int batch = 0;
            for (Object[] row : s.rows()) {
                for (int i = 0; i < row.length; i++) ps.setObject(i + 1, row[i]);
                ps.addBatch();
                if (++batch % 1000 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
    }

    /** Ejecuta una consulta de solo lectura. El mensaje de la excepción sirve para que el agente corrija la consulta. */
    Result query(String sql) {
        String q = sql == null ? "" : sql.strip();
        while (q.endsWith(";")) q = q.substring(0, q.length() - 1).strip();
        if (q.isEmpty()) throw new IllegalArgumentException("La consulta está vacía.");
        if (q.length() > 10_000) throw new IllegalArgumentException("La consulta es demasiado larga.");
        if (q.contains(";")) throw new IllegalArgumentException("Una sola consulta por vez, sin «;».");
        if (!READ_ONLY.matcher(q).matches()) throw new IllegalArgumentException("Solo se permiten consultas SELECT (o WITH … SELECT).");
        try (Statement st = reader.createStatement()) {
            st.setQueryTimeout(20);
            st.setMaxRows(MAX_RESULT_ROWS + 1);
            try (ResultSet rs = st.executeQuery(q)) {
                ResultSetMetaData md = rs.getMetaData();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) columns.add(md.getColumnLabel(i).toLowerCase(Locale.ROOT));
                List<List<Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() == MAX_RESULT_ROWS) {
                        truncated = true;
                        break;
                    }
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= columns.size(); i++) row.add(normalize(rs.getObject(i)));
                    rows.add(row);
                }
                return new Result(columns, rows, truncated);
            }
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "error" : e.getMessage().lines().findFirst().orElse("error");
            throw new IllegalArgumentException("La consulta falló: " + message, e);
        }
    }

    private static Object normalize(Object v) {
        if (v instanceof BigDecimal n) return n.stripTrailingZeros().scale() < 0 ? n.setScale(0) : n.stripTrailingZeros();
        if (v instanceof Double || v instanceof Float) return BigDecimal.valueOf(((Number) v).doubleValue()).stripTrailingZeros();
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        if (v instanceof LocalDate || v instanceof LocalDateTime) return v;
        return v;
    }

    static String quote(String identifier) {
        return "\"" + identifier.toUpperCase(Locale.ROOT).replace("\"", "") + "\"";
    }

    @Override
    public void close() {
        try {
            reader.close();
        } catch (SQLException ignored) {
            // la base se descarta igual
        }
        try {
            admin.close();
        } catch (SQLException ignored) {
            // la base se descarta igual
        }
    }
}
