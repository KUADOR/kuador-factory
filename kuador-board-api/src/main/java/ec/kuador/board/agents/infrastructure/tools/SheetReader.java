package ec.kuador.board.agents.infrastructure.tools;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.ReadingOptions;
import org.dhatim.fastexcel.reader.Row;

/**
 * Lee planillas (Excel .xlsx y CSV) como tablas con nombres de columna simples y tipos inferidos. Busca la fila de
 * encabezados (los reportes exportados suelen traer un título arriba) y descarta filas vacías.
 */
final class SheetReader {

    static final int MAX_ROWS = 200_000;
    private static final int HEADER_SCAN = 20;
    private static final Pattern DATE_FORMAT = Pattern.compile("(?i)(^|[^\"\\\\])(y|d|mmm)");
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern ISO_DATETIME = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(:\\d{2})?$");
    private static final Pattern LATAM_DATE = Pattern.compile("^\\d{1,2}/\\d{1,2}/\\d{4}$");
    private static final Pattern NUMBER = Pattern.compile("^-?\\$?\\s?-?[0-9][0-9.,]*$");
    private static final DateTimeFormatter LATAM = DateTimeFormatter.ofPattern("d/M/uuuu");
    /** Palabras reservadas de H2: una columna con ese nombre lleva «_» al final. */
    private static final Set<String> RESERVED = Set.of("all", "and", "any", "array", "as", "asymmetric", "authorization", "between", "both", "case",
            "cast", "check", "constraint", "cross", "current_catalog", "current_date", "current_path", "current_role", "current_schema",
            "current_time", "current_timestamp", "current_user", "day", "default", "distinct", "else", "end", "except", "exists", "false", "fetch",
            "for", "foreign", "from", "full", "group", "groups", "having", "hour", "if", "ilike", "in", "inner", "intersect", "interval", "is",
            "join", "key", "leading", "left", "like", "limit", "localtime", "localtimestamp", "minus", "minute", "month", "natural", "not", "null",
            "offset", "on", "or", "order", "over", "partition", "primary", "qualify", "range", "regexp", "right", "row", "rownum", "rows",
            "second", "select", "session_user", "set", "some", "symmetric", "system_user", "table", "to", "top", "trailing", "true", "uescape",
            "union", "unique", "unknown", "user", "using", "value", "values", "when", "where", "window", "with", "year");

    private SheetReader() {}

    static boolean isExcel(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".xlsx") || name.toLowerCase(Locale.ROOT).endsWith(".xlsm");
    }

    static boolean isCsv(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".csv") || n.endsWith(".tsv");
    }

    /** Las hojas con datos de un Excel. {@code taken}: nombres de tabla ya usados (se completan). */
    static List<Sheet> excel(String fileName, byte[] content, Set<String> taken) throws IOException {
        List<Sheet> sheets = new ArrayList<>();
        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(content), new ReadingOptions(true, false))) {
            List<org.dhatim.fastexcel.reader.Sheet> all = wb.getSheets().toList();
            for (org.dhatim.fastexcel.reader.Sheet sheet : all) {
                List<List<Object>> raw = new ArrayList<>();
                boolean truncated = false;
                try (var rows = sheet.openStream()) {
                    for (Row row : (Iterable<Row>) rows::iterator) {
                        if (raw.size() > MAX_ROWS) {
                            truncated = true;
                            break;
                        }
                        List<Object> values = new ArrayList<>();
                        for (int i = 0; i < row.getCellCount(); i++) values.add(value(row.getCell(i)));
                        raw.add(values);
                    }
                }
                String base = all.size() == 1 ? baseName(fileName) : baseName(fileName) + "_" + sheet.getName();
                Sheet s = build(unique(identifier(base, "tabla"), taken), fileName + (all.size() == 1 ? "" : " · hoja «" + sheet.getName() + "»"), raw, truncated);
                if (s != null) sheets.add(s);
            }
        }
        return sheets;
    }

    static Sheet csv(String fileName, byte[] content, Set<String> taken) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        char sep = separator(text);
        List<List<Object>> raw = new ArrayList<>();
        boolean truncated = false;
        for (List<String> line : parseCsv(text, sep)) {
            if (raw.size() > MAX_ROWS) {
                truncated = true;
                break;
            }
            raw.add(new ArrayList<>(line.stream().map(SheetReader::parseText).toList()));
        }
        return build(unique(identifier(baseName(fileName), "tabla"), taken), fileName, raw, truncated);
    }

    private static Object value(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getType()) {
            case NUMBER -> isDate(cell) ? date(cell.asDate()) : cell.asNumber();
            case STRING -> blankToNull(cell.asString());
            case BOOLEAN -> cell.asBoolean();
            case FORMULA -> {
                String raw = cell.getRawValue();
                if (raw == null || raw.isBlank()) yield null;
                try {
                    BigDecimal n = new BigDecimal(raw.strip());
                    yield isDate(cell) ? date(cell.asDate()) : n;
                } catch (RuntimeException e) {
                    yield blankToNull(raw);
                }
            }
            case EMPTY, ERROR -> null;
        };
    }

    private static boolean isDate(Cell cell) {
        String format = cell.getDataFormatString();
        Integer id = cell.getDataFormatId();
        if (id != null && ((id >= 14 && id <= 22) || (id >= 45 && id <= 47))) return true;
        return format != null && !format.equalsIgnoreCase("General") && DATE_FORMAT.matcher(format.replaceAll("\\[[^]]*]", "")).find();
    }

    private static Object date(LocalDateTime t) {
        if (t == null) return null;
        return t.toLocalTime().toSecondOfDay() == 0 ? t.toLocalDate() : t;
    }

    /** Arma la tabla: busca los encabezados, nombra columnas e infiere tipos. Sin datos, null. */
    static Sheet build(String table, String source, List<List<Object>> raw, boolean truncated) {
        int width = raw.stream().mapToInt(List::size).max().orElse(0);
        if (width == 0) return null;
        int header = headerRow(raw);
        if (header < 0) return null;
        List<Object> head = raw.get(header);
        int first = Integer.MAX_VALUE;
        int last = -1;
        for (List<Object> r : raw.subList(header, raw.size())) {
            for (int i = 0; i < r.size(); i++) {
                if (r.get(i) != null) {
                    first = Math.min(first, i);
                    last = Math.max(last, i);
                }
            }
        }
        if (last < 0) return null;
        List<String> headers = new ArrayList<>();
        Set<String> names = new HashSet<>();
        List<String> columnNames = new ArrayList<>();
        for (int i = first; i <= last; i++) {
            Object h = i < head.size() ? head.get(i) : null;
            String label = h == null ? "columna " + (i - first + 1) : text(h);
            headers.add(label);
            columnNames.add(unique(identifier(label, "columna_" + (i - first + 1)), names));
        }
        List<Object[]> rows = new ArrayList<>();
        for (List<Object> r : raw.subList(header + 1, raw.size())) {
            Object[] values = new Object[last - first + 1];
            boolean any = false;
            for (int i = first; i <= last; i++) {
                Object v = i < r.size() ? r.get(i) : null;
                values[i - first] = v;
                any |= v != null;
            }
            if (any) rows.add(values);
        }
        List<Sheet.Column> columns = new ArrayList<>();
        for (int c = 0; c < columnNames.size(); c++) {
            Sheet.Type type = infer(rows, c);
            columns.add(new Sheet.Column(columnNames.get(c), headers.get(c), type));
            for (Object[] r : rows) r[c] = coerce(r[c], type);
        }
        return new Sheet(table, source, columns, rows, truncated);
    }

    /** Primera fila (entre las primeras) con al menos la mitad de las celdas llenas y todas de texto. */
    private static int headerRow(List<List<Object>> raw) {
        int scan = Math.min(HEADER_SCAN, raw.size());
        int widest = 0;
        for (int i = 0; i < scan; i++) widest = Math.max(widest, (int) raw.get(i).stream().filter(v -> v != null).count());
        for (int i = 0; i < scan; i++) {
            List<Object> r = raw.get(i);
            long filled = r.stream().filter(v -> v != null).count();
            if (filled >= Math.max(1, Math.min(2, widest)) && filled * 2 >= widest && r.stream().filter(v -> v != null).allMatch(v -> v instanceof String)) {
                return i;
            }
        }
        for (int i = 0; i < raw.size(); i++) if (raw.get(i).stream().anyMatch(v -> v != null)) return i;
        return -1;
    }

    private static Sheet.Type infer(List<Object[]> rows, int c) {
        Sheet.Type type = null;
        for (Object[] r : rows) {
            Object v = r[c];
            if (v == null) continue;
            Sheet.Type t = v instanceof BigDecimal ? Sheet.Type.NUMBER
                    : v instanceof LocalDate ? Sheet.Type.DATE
                    : v instanceof LocalDateTime ? Sheet.Type.TIMESTAMP
                    : v instanceof Boolean ? Sheet.Type.BOOLEAN : Sheet.Type.TEXT;
            if (type == null) type = t;
            else if (type != t) {
                if ((type == Sheet.Type.DATE && t == Sheet.Type.TIMESTAMP) || (type == Sheet.Type.TIMESTAMP && t == Sheet.Type.DATE)) type = Sheet.Type.TIMESTAMP;
                else return Sheet.Type.TEXT;
            }
        }
        return type == null ? Sheet.Type.TEXT : type;
    }

    private static Object coerce(Object v, Sheet.Type type) {
        if (v == null) return null;
        return switch (type) {
            case TEXT -> text(v);
            case TIMESTAMP -> v instanceof LocalDate d ? d.atStartOfDay() : v;
            default -> v;
        };
    }

    static String text(Object v) {
        if (v instanceof BigDecimal n) return n.stripTrailingZeros().toPlainString();
        return v.toString().strip();
    }

    /** Un texto de CSV como número, fecha o texto. Acepta «1.234,56», «1,234.56», «$ 20» y fechas ISO o d/m/aaaa. */
    static Object parseText(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.isEmpty()) return null;
        if (ISO_DATE.matcher(s).matches()) {
            try {
                return LocalDate.parse(s);
            } catch (DateTimeParseException ignored) {
                return s;
            }
        }
        if (ISO_DATETIME.matcher(s).matches()) {
            try {
                return LocalDateTime.parse(s.replace(' ', 'T').length() == 16 ? s.replace(' ', 'T') + ":00" : s.replace(' ', 'T'));
            } catch (DateTimeParseException ignored) {
                return s;
            }
        }
        if (LATAM_DATE.matcher(s).matches()) {
            try {
                return LocalDate.parse(s, LATAM);
            } catch (DateTimeParseException ignored) {
                return s;
            }
        }
        // Con cero adelante (teléfonos, cédulas, códigos) es texto: como número perdería el cero.
        if (NUMBER.matcher(s).matches() && !(s.length() > 1 && s.charAt(0) == '0' && Character.isDigit(s.charAt(1)))) {
            String n = s.replace("$", "").replace(" ", "");
            int dot = n.lastIndexOf('.');
            int comma = n.lastIndexOf(',');
            if (dot >= 0 && comma >= 0) n = dot > comma ? n.replace(",", "") : n.replace(".", "").replace(',', '.');
            else if (comma >= 0) n = n.indexOf(',') == comma && n.length() - comma - 1 != 3 ? n.replace(',', '.') : n.replace(",", "");
            else if (dot >= 0 && n.indexOf('.') != dot) n = n.replace(".", "");
            try {
                return new BigDecimal(n);
            } catch (NumberFormatException ignored) {
                return s;
            }
        }
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) return Boolean.parseBoolean(s);
        return s;
    }

    private static char separator(String text) {
        String first = text.lines().findFirst().orElse("");
        char best = ',';
        long most = -1;
        for (char c : new char[] {',', ';', '\t', '|'}) {
            long n = first.chars().filter(ch -> ch == c).count();
            if (n > most) {
                most = n;
                best = c;
            }
        }
        return best;
    }

    /** CSV con comillas dobles (campos con separadores, saltos de línea y comillas escapadas «""»). */
    static List<List<String>> parseCsv(String text, char sep) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else quoted = false;
                } else field.append(c);
            } else if (c == '"' && field.isEmpty()) {
                quoted = true;
            } else if (c == sep) {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else field.append(c);
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** Nombre SQL simple: minúsculas, sin tildes, solo letras, números y «_»; nunca una palabra reservada. */
    static String identifier(String label, String fallback) {
        String s = Normalizer.normalize(label, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.length() > 50) s = s.substring(0, 50).replaceAll("_+$", "");
        if (s.isEmpty()) s = fallback;
        if (Character.isDigit(s.charAt(0))) s = "c_" + s;
        if (RESERVED.contains(s)) s = s + "_";
        return s;
    }

    private static String unique(String name, Set<String> taken) {
        String candidate = name;
        for (int i = 2; !taken.add(candidate); i++) candidate = name + "_" + i;
        return candidate;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
