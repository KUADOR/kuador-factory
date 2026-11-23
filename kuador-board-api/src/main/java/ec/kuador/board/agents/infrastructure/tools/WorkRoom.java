package ec.kuador.board.agents.infrastructure.tools;

import ec.kuador.board.shared.files.DocumentText;
import ec.kuador.board.agents.domain.BoardAgentModel.InputFile;
import ec.kuador.board.agents.domain.BoardAgentModel.OutputFile;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * El espacio de trabajo de un agente que no tiene ejecución de código propia (proveedores como OpenAI o DeepSeek):
 * los adjuntos de la tarjeta, sus planillas cargadas como tablas y las herramientas para leerlos, consultarlos y
 * crear documentos. El agente decide qué usar según lo que pide la tarjeta. Vive lo que dura el trabajo.
 */
public final class WorkRoom implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkRoom.class);
    private static final int READ_CHUNK = 30_000;
    private static final int MAX_OUTPUTS = 10;
    private static final int PREVIEW_ROWS = 5;

    /** Una herramienta para el modelo: nombre, para qué sirve y el esquema JSON de sus argumentos. */
    public record ToolSpec(String name, String description, Map<String, Object> parameters) {}

    private record Doc(InputFile file, DocumentText.Extracted text, List<Sheet> sheets) {}

    private final Map<String, Doc> docs = new LinkedHashMap<>();
    private final List<Sheet> sheets = new ArrayList<>();
    private final DataRoom data;
    private final List<OutputFile> outputs = new ArrayList<>();

    public WorkRoom(List<InputFile> files) {
        Set<String> tables = new HashSet<>();
        for (InputFile f : files) {
            List<Sheet> own = new ArrayList<>();
            DocumentText.Extracted text;
            try {
                if (SheetReader.isExcel(f.name())) {
                    own.addAll(SheetReader.excel(f.name(), f.content(), tables));
                    text = new DocumentText.Extracted("Excel", null, null);
                } else if (SheetReader.isCsv(f.name())) {
                    Sheet s = SheetReader.csv(f.name(), f.content(), tables);
                    if (s != null) own.add(s);
                    text = new DocumentText.Extracted("CSV", new String(f.content(), StandardCharsets.UTF_8), null);
                } else {
                    text = DocumentText.extract(f.name(), f.contentType(), f.content());
                }
            } catch (Exception e) {
                log.debug("No se pudo leer {}: {}", f.name(), e.getMessage());
                text = new DocumentText.Extracted("planilla", null, "No se pudo leer la planilla (¿dañada o protegida?).");
            }
            sheets.addAll(own);
            docs.put(f.name(), new Doc(f, text, own));
        }
        this.data = sheets.isEmpty() ? null : new DataRoom(sheets);
    }

    /** Qué hay en la tarjeta, para el mensaje inicial: archivos, cómo leerlos y las tablas con sus columnas. */
    public String overview() {
        if (docs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Archivos de la tarjeta:\n");
        for (Doc d : docs.values()) {
            sb.append("- «").append(d.file().name()).append("» (").append(d.text().kind()).append(")");
            if (d.text().text() != null && d.sheets().isEmpty()) sb.append(": ").append(d.text().text().length()).append(" caracteres; léelo con read_file.");
            if (d.text().note() != null) sb.append(" ").append(d.text().note());
            sb.append("\n");
            for (Sheet s : d.sheets()) {
                sb.append("  Tabla ").append(s.table()).append(" (").append(s.source()).append(", ").append(s.rows().size()).append(" filas")
                        .append(s.truncated() ? ", recortada" : "").append("). Columnas: ");
                List<String> cols = new ArrayList<>();
                for (Sheet.Column c : s.columns()) {
                    cols.add(c.name() + " " + c.type().name() + (c.name().equals(SheetReader.identifier(c.header(), c.name())) ? "" : " («" + c.header() + "»)"));
                }
                sb.append(String.join(", ", cols)).append(".\n  Primeras filas:\n");
                sb.append(indent(markdown(s.columns().stream().map(Sheet.Column::name).toList(),
                        s.rows().stream().limit(PREVIEW_ROWS).map(r -> java.util.Arrays.asList((Object[]) r)).toList())));
            }
        }
        return sb.toString();
    }

    public List<ToolSpec> tools() {
        List<ToolSpec> tools = new ArrayList<>();
        tools.add(new ToolSpec("read_file", "Lee el texto de un archivo de la tarjeta (PDF, Word, Excel, CSV, texto). Los textos largos vienen por partes.",
                object(Map.of("name", string("Nombre exacto del archivo."),
                        "offset", Map.of("type", "integer", "description", "Desde qué carácter seguir leyendo (0 al empezar).")), "name")));
        if (data != null) {
            tools.add(new ToolSpec("query_data", "Consulta SQL de solo lectura (sintaxis H2) sobre las tablas de las planillas. Úsala para toda cifra: totales, "
                    + "promedios, conteos, rankings, agrupaciones por fecha. Devuelve hasta " + DataRoom.MAX_RESULT_ROWS + " filas.",
                    object(Map.of("sql", string("Una consulta SELECT. Funciones útiles: SUM, AVG, COUNT, ROUND(x, 2), EXTRACT(MONTH FROM fecha), "
                            + "FORMATDATETIME(fecha, 'yyyy-MM'), DAY_OF_WEEK(fecha).")), "sql")));
            tools.add(new ToolSpec("create_csv", "Crea un archivo CSV para entregar con el resultado de una consulta (se abre en Excel).",
                    object(Map.of("file_name", string("Nombre del archivo, termina en .csv."), "sql", string("La consulta SELECT.")), "file_name", "sql")));
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", Map.of("type", "string", "enum", ReportPdf.BLOCK_TYPES));
        props.put("text", string("heading y paragraph: el texto (en paragraph, **negrita** resalta)."));
        props.put("items", Map.of("type", "array", "description", "bullets: lista de textos. kpis: lista de {label, value, note} (value ya formateado, p. ej. \"$12,450\").",
                "items", Map.of()));
        props.put("title", string("bar_chart y line_chart: título del gráfico."));
        props.put("sql", string("table, bar_chart y line_chart: consulta que da los datos (en gráficos: primera columna etiqueta, segunda valor)."));
        props.put("columns", Map.of("type", "array", "items", Map.of("type", "string"), "description", "table sin sql: encabezados."));
        props.put("rows", Map.of("type", "array", "items", Map.of("type", "array", "items", Map.of()), "description", "table sin sql: filas."));
        props.put("labels", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Gráfico sin sql: etiquetas."));
        props.put("values", Map.of("type", "array", "items", Map.of("type", "number"), "description", "Gráfico sin sql: valores."));
        block.put("properties", props);
        block.put("required", List.of("type"));
        tools.add(new ToolSpec("create_document", "Crea un documento PDF para entregar: informe, resumen tipo tablero, propuesta, acta… Tú escribes el contenido "
                + "en bloques (títulos, párrafos, viñetas, indicadores destacados, tablas y gráficos de barras o líneas).",
                object(Map.of("file_name", string("Nombre del archivo, termina en .pdf."), "title", string("Título del documento."),
                        "subtitle", string("Subtítulo opcional: periodo, fuente o fecha."),
                        "blocks", Map.of("type", "array", "items", block, "description", "El contenido, en orden.")), "file_name", "title", "blocks")));
        return tools;
    }

    /** Ejecuta una herramienta. Los errores vuelven como texto para que el agente corrija y siga. */
    public String call(String name, JsonNode args) {
        try {
            return switch (name) {
                case "read_file" -> read(args.path("name").asString(""), args.path("offset").asInt(0));
                case "query_data" -> {
                    DataRoom.Result r = requireData().query(args.path("sql").asString(""));
                    yield markdown(r.columns(), r.rows()) + (r.truncated() ? "(Hay más de " + DataRoom.MAX_RESULT_ROWS + " filas: agrupa o filtra.)\n" : "")
                            + "(" + r.rows().size() + " filas)";
                }
                case "create_csv" -> {
                    DataRoom.Result r = requireData().query(args.path("sql").asString(""));
                    String file = fileName(args.path("file_name").asString(""), ".csv");
                    add(new OutputFile(file, "text/csv", csv(r).getBytes(StandardCharsets.UTF_8)));
                    yield "Listo: " + file + " (" + r.rows().size() + " filas) quedará adjunto al entregar.";
                }
                case "create_document" -> {
                    String file = fileName(args.path("file_name").asString(""), ".pdf");
                    String title = args.path("title").asString("").strip();
                    if (title.isEmpty()) throw new IllegalArgumentException("Falta el título.");
                    byte[] pdf = ReportPdf.render(title, args.path("subtitle").asString(""), args.path("blocks"), data == null ? null : data::query);
                    add(new OutputFile(file, "application/pdf", pdf));
                    yield "Listo: " + file + " (" + (pdf.length / 1024 + 1) + " KB) quedará adjunto al entregar.";
                }
                default -> "Error: no existe la herramienta " + name + ".";
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "Error: " + e.getMessage();
        }
    }

    public List<OutputFile> outputs() {
        return List.copyOf(outputs);
    }

    private String read(String name, int offset) {
        Doc d = docs.get(name);
        if (d == null) throw new IllegalArgumentException("No hay un archivo «" + name + "». Los archivos son: " + String.join(", ", docs.keySet()) + ".");
        String text = d.text().text();
        if (!d.sheets().isEmpty() && text == null) {
            StringBuilder sb = new StringBuilder("Es una planilla cargada como tabla: consúltala con query_data.\n");
            for (Sheet s : d.sheets()) {
                sb.append("Tabla ").append(s.table()).append(" (").append(s.rows().size()).append(" filas), primeras 30:\n");
                sb.append(markdown(s.columns().stream().map(Sheet.Column::name).toList(), s.rows().stream().limit(30).map(r -> java.util.Arrays.asList((Object[]) r)).toList()));
            }
            return sb.toString();
        }
        if (text == null) throw new IllegalArgumentException(d.text().note() == null ? "No se puede leer ese archivo." : d.text().note());
        int from = Math.max(0, Math.min(offset, text.length()));
        int to = Math.min(text.length(), from + READ_CHUNK);
        String chunk = text.substring(from, to);
        return to < text.length() ? chunk + "\n\n(Sigue: llama a read_file con offset=" + to + "; total " + text.length() + " caracteres.)" : chunk;
    }

    private DataRoom requireData() {
        if (data == null) throw new IllegalArgumentException("No hay planillas cargadas en esta tarjeta.");
        return data;
    }

    private void add(OutputFile file) {
        outputs.removeIf(o -> o.name().equals(file.name()));
        if (outputs.size() >= MAX_OUTPUTS) throw new IllegalArgumentException("Ya creaste " + MAX_OUTPUTS + " archivos; es el máximo por trabajo.");
        outputs.add(file);
    }

    private static String fileName(String raw, String extension) {
        String name = raw.strip().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (name.isEmpty()) throw new IllegalArgumentException("Falta el nombre del archivo.");
        if (name.length() > 120) name = name.substring(0, 120);
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(extension) ? name : name + extension;
    }

    static String markdown(List<String> columns, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder("| ").append(String.join(" | ", columns)).append(" |\n|");
        sb.append(" --- |".repeat(columns.size())).append("\n");
        for (List<Object> row : rows) {
            sb.append("|");
            for (Object v : row) sb.append(" ").append(v == null ? "" : cell(v).replace("|", "/").replace("\n", " ")).append(" |");
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String csv(DataRoom.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", r.columns().stream().map(WorkRoom::csvField).toList())).append("\r\n");
        for (List<Object> row : r.rows()) sb.append(String.join(",", row.stream().map(v -> v == null ? "" : csvField(cell(v))).toList())).append("\r\n");
        return sb.toString();
    }

    private static String csvField(String v) {
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static String cell(Object v) {
        return SheetReader.text(v);
    }

    private static String indent(String s) {
        return s.lines().map(l -> "  " + l).reduce("", (a, b) -> a + b + "\n");
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> object(Map<String, Object> properties, String... required) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("type", "object");
        o.put("properties", new LinkedHashMap<>(properties));
        o.put("required", List.of(required));
        return o;
    }

    @Override
    public void close() {
        if (data != null) data.close();
    }
}
