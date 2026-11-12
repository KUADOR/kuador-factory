package ec.kuador.board.agents.infrastructure.tools;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.ListItem;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * Arma un PDF a partir de bloques que escribe el agente: títulos, párrafos, viñetas, indicadores destacados (tipo
 * tablero), tablas y gráficos de barras o líneas. Tablas y gráficos aceptan una consulta para que sus cifras salgan
 * exactas de los datos. El agente decide el contenido; esto solo le da forma.
 */
final class ReportPdf {

    static final List<String> BLOCK_TYPES = List.of("heading", "paragraph", "bullets", "kpis", "table", "bar_chart", "line_chart", "page_break");
    private static final int MAX_TABLE_ROWS = 500;
    private static final int MAX_CHART_POINTS = 60;

    private static final Color INK = new Color(0x1F, 0x29, 0x37);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color LINE = new Color(0xE5, 0xE7, 0xEB);
    private static final Color SOFT = new Color(0xF3, 0xF4, 0xF6);
    private static final Color ACCENT = new Color(0x0F, 0x76, 0x6E);

    private static final Font TITLE = new Font(Font.HELVETICA, 20, Font.BOLD, INK);
    private static final Font SUBTITLE = new Font(Font.HELVETICA, 11, Font.NORMAL, MUTED);
    private static final Font HEADING = new Font(Font.HELVETICA, 13.5f, Font.BOLD, INK);
    private static final Font BODY = new Font(Font.HELVETICA, 10.5f, Font.NORMAL, INK);
    private static final Font BODY_BOLD = new Font(Font.HELVETICA, 10.5f, Font.BOLD, INK);
    private static final Font SMALL = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, MUTED);
    private static final Font KPI = new Font(Font.HELVETICA, 17, Font.BOLD, INK);
    private static final Font CELL = new Font(Font.HELVETICA, 9, Font.NORMAL, INK);
    private static final Font CELL_HEAD = new Font(Font.HELVETICA, 9, Font.BOLD, INK);

    private ReportPdf() {}

    /** {@code data}: ejecuta una consulta (nula si no hay planillas). Lanza IllegalArgumentException con un motivo claro. */
    static byte[] render(String title, String subtitle, JsonNode blocks, Function<String, DataRoom.Result> data) {
        if (!blocks.isArray() || blocks.isEmpty()) throw new IllegalArgumentException("El documento necesita al menos un bloque.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48, 48, 54, 54);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new PageNumbers());
            doc.addTitle(title);
            doc.open();
            Paragraph t = new Paragraph(title, TITLE);
            t.setSpacingAfter(subtitle == null || subtitle.isBlank() ? 14 : 2);
            doc.add(t);
            if (subtitle != null && !subtitle.isBlank()) {
                Paragraph s = new Paragraph(subtitle, SUBTITLE);
                s.setSpacingAfter(14);
                doc.add(s);
            }
            int index = 0;
            for (JsonNode b : blocks) {
                index++;
                try {
                    block(doc, writer, b, data);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Bloque " + index + " (" + b.path("type").asString("?") + "): " + e.getMessage(), e);
                }
            }
            doc.close();
        } catch (DocumentException e) {
            throw new IllegalArgumentException("No se pudo armar el PDF: " + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    private static void block(Document doc, PdfWriter writer, JsonNode b, Function<String, DataRoom.Result> data) throws DocumentException {
        String type = b.path("type").asString("");
        switch (type) {
            case "heading" -> {
                Paragraph p = new Paragraph(required(b, "text"), HEADING);
                p.setSpacingBefore(12);
                p.setSpacingAfter(6);
                p.setKeepTogether(true);
                doc.add(p);
            }
            case "paragraph" -> {
                Paragraph p = rich(required(b, "text"));
                p.setSpacingAfter(8);
                p.setLeading(15);
                doc.add(p);
            }
            case "bullets" -> {
                com.lowagie.text.List list = new com.lowagie.text.List(false, 12);
                list.setListSymbol(new Chunk("•  ", BODY));
                for (JsonNode item : items(b)) {
                    ListItem li = new ListItem(rich(item.asString("")));
                    li.setLeading(15);
                    list.add(li);
                }
                doc.add(list);
                doc.add(spacer(6));
            }
            case "kpis" -> doc.add(kpis(items(b)));
            case "table" -> {
                DataRoom.Result r = rows(b, data);
                doc.add(table(r));
            }
            case "bar_chart", "line_chart" -> {
                DataRoom.Result r = rows(b, data);
                doc.add(chart(writer, b.path("title").asString(""), r, type.equals("line_chart"), doc.right() - doc.left()));
            }
            case "page_break" -> doc.newPage();
            default -> throw new IllegalArgumentException("tipo desconocido; usa uno de " + BLOCK_TYPES + ".");
        }
    }

    /** Párrafo con **negritas** simples. */
    private static Paragraph rich(String text) {
        Paragraph p = new Paragraph();
        String[] parts = text.split("\\*\\*", -1);
        for (int i = 0; i < parts.length; i++) if (!parts[i].isEmpty()) p.add(new Chunk(parts[i], i % 2 == 1 ? BODY_BOLD : BODY));
        return p;
    }

    private static PdfPTable kpis(JsonNode items) throws DocumentException {
        int n = Math.min(4, Math.max(1, items.size()));
        PdfPTable table = new PdfPTable(n);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setSpacingAfter(12);
        for (JsonNode item : items) {
            PdfPCell cell = new PdfPCell();
            cell.setBorderColor(LINE);
            cell.setPadding(10);
            cell.setBackgroundColor(Color.WHITE);
            cell.addElement(new Paragraph(item.path("label").asString(""), SMALL));
            Paragraph value = new Paragraph(item.path("value").asString(""), KPI);
            value.setSpacingBefore(2);
            cell.addElement(value);
            String note = item.path("note").asString("");
            if (!note.isBlank()) cell.addElement(new Paragraph(note, SMALL));
            table.addCell(cell);
        }
        table.completeRow();
        return table;
    }

    private static PdfPTable table(DataRoom.Result r) throws DocumentException {
        if (r.columns().isEmpty()) throw new IllegalArgumentException("la tabla no tiene columnas.");
        PdfPTable table = new PdfPTable(r.columns().size());
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.setSpacingBefore(4);
        table.setSpacingAfter(12);
        boolean[] numeric = new boolean[r.columns().size()];
        for (int c = 0; c < numeric.length; c++) {
            final int col = c;
            numeric[c] = !r.rows().isEmpty() && r.rows().stream().allMatch(row -> col >= row.size() || row.get(col) == null || number(row.get(col)) != null);
        }
        for (int c = 0; c < numeric.length; c++) {
            PdfPCell h = new PdfPCell(new Phrase(r.columns().get(c), CELL_HEAD));
            style(h, numeric[c]);
            h.setBackgroundColor(SOFT);
            table.addCell(h);
        }
        int count = 0;
        for (List<Object> row : r.rows()) {
            if (++count > MAX_TABLE_ROWS) break;
            for (int c = 0; c < numeric.length; c++) {
                Object v = c < row.size() ? row.get(c) : null;
                PdfPCell cell = new PdfPCell(new Phrase(v == null ? "" : numeric[c] ? format(number(v)) : SheetReader.text(v), CELL));
                style(cell, numeric[c]);
                table.addCell(cell);
            }
        }
        return table;
    }

    private static void style(PdfPCell cell, boolean right) {
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(LINE);
        cell.setPaddingTop(4);
        cell.setPaddingBottom(5);
        cell.setHorizontalAlignment(right ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
    }

    /** Gráfico dibujado a mano: etiquetas en la primera columna y valores en la segunda. */
    private static Image chart(PdfWriter writer, String title, DataRoom.Result r, boolean line, float width) {
        if (r.columns().size() < 2) throw new IllegalArgumentException("el gráfico necesita dos columnas: etiqueta y valor.");
        List<String> labels = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (List<Object> row : r.rows()) {
            if (labels.size() == MAX_CHART_POINTS) break;
            BigDecimal v = row.size() > 1 ? number(row.get(1)) : null;
            if (v == null) continue;
            labels.add(row.get(0) == null ? "" : SheetReader.text(row.get(0)));
            values.add(v.doubleValue());
        }
        if (values.isEmpty()) throw new IllegalArgumentException("el gráfico no tiene valores numéricos.");
        float height = 230;
        PdfContentByte cb = writer.getDirectContent();
        PdfTemplate tpl = cb.createTemplate(width, height);
        BaseFont font = SMALL.getCalculatedBaseFont(false);
        BaseFont bold = BODY_BOLD.getCalculatedBaseFont(false);

        float top = height - (title.isBlank() ? 10 : 26);
        if (!title.isBlank()) text(tpl, bold, 10.5f, INK, title, 0, height - 14, 0, Element.ALIGN_LEFT);
        boolean rotate = labels.size() > 8;
        float bottom = rotate ? 62 : 22;
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double min = Math.min(0, values.stream().mapToDouble(Double::doubleValue).min().orElse(0));
        double step = niceStep((max - min) / 4);
        double axisMin = Math.floor(min / step) * step;
        double axisMax = Math.max(Math.ceil(max / step) * step, axisMin + step);
        float left = 8 + Math.max(font.getWidthPoint(format(BigDecimal.valueOf(axisMax)), 7.5f), font.getWidthPoint(format(BigDecimal.valueOf(axisMin)), 7.5f));
        float plotW = width - left - 6;
        float plotH = top - bottom;
        java.util.function.DoubleUnaryOperator y = v -> bottom + (float) ((v - axisMin) / (axisMax - axisMin) * plotH);

        for (double g = axisMin; g <= axisMax + step / 2; g += step) {
            float gy = (float) y.applyAsDouble(g);
            tpl.setColorStroke(LINE);
            tpl.setLineWidth(0.6f);
            tpl.moveTo(left, gy);
            tpl.lineTo(left + plotW, gy);
            tpl.stroke();
            text(tpl, font, 7.5f, MUTED, format(BigDecimal.valueOf(g)), left - 4, gy - 2.5f, 0, Element.ALIGN_RIGHT);
        }
        float slot = plotW / values.size();
        boolean showValues = values.size() <= 15;
        float prevX = 0;
        float prevY = 0;
        for (int i = 0; i < values.size(); i++) {
            float cx = left + slot * i + slot / 2;
            float vy = (float) y.applyAsDouble(values.get(i));
            float zero = (float) y.applyAsDouble(0);
            if (line) {
                if (i > 0) {
                    tpl.setColorStroke(ACCENT);
                    tpl.setLineWidth(1.6f);
                    tpl.moveTo(prevX, prevY);
                    tpl.lineTo(cx, vy);
                    tpl.stroke();
                }
                tpl.setColorFill(ACCENT);
                tpl.circle(cx, vy, 2f);
                tpl.fill();
                prevX = cx;
                prevY = vy;
            } else {
                float barW = Math.min(36, slot * 0.66f);
                tpl.setColorFill(ACCENT);
                tpl.rectangle(cx - barW / 2, Math.min(zero, vy), barW, Math.abs(vy - zero));
                tpl.fill();
            }
            if (showValues) text(tpl, font, 7, INK, format(BigDecimal.valueOf(values.get(i))), cx, vy + 4, 0, Element.ALIGN_CENTER);
            String label = labels.get(i).length() > 18 ? labels.get(i).substring(0, 17) + "…" : labels.get(i);
            if (rotate) text(tpl, font, 7, MUTED, label, cx + 2, bottom - 6, 45, Element.ALIGN_RIGHT);
            else text(tpl, font, 7.5f, MUTED, label, cx, bottom - 12, 0, Element.ALIGN_CENTER);
        }
        try {
            Image img = Image.getInstance(tpl);
            img.setSpacingBefore(6);
            img.setSpacingAfter(12);
            return img;
        } catch (com.lowagie.text.BadElementException e) {
            throw new IllegalArgumentException("no se pudo dibujar el gráfico.", e);
        }
    }

    private static void text(PdfTemplate tpl, BaseFont font, float size, Color color, String text, float x, float y, float rotation, int align) {
        tpl.beginText();
        tpl.setFontAndSize(font, size);
        tpl.setColorFill(color);
        tpl.showTextAligned(align, text, x, y, rotation);
        tpl.endText();
    }

    private static double niceStep(double raw) {
        if (raw <= 0) return 1;
        double exp = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / exp;
        return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * exp;
    }

    /** Filas de un bloque: de una consulta ({@code sql}) o escritas ({@code columns} y {@code rows}, o {@code labels} y {@code values}). */
    private static DataRoom.Result rows(JsonNode b, Function<String, DataRoom.Result> data) {
        String sql = b.path("sql").asString("");
        if (!sql.isBlank()) {
            if (data == null) throw new IllegalArgumentException("no hay planillas cargadas para consultar; escribe los datos en el bloque.");
            return data.apply(sql);
        }
        if (b.has("labels")) {
            List<List<Object>> rows = new ArrayList<>();
            JsonNode labels = b.path("labels");
            JsonNode values = b.path("values");
            for (int i = 0; i < labels.size(); i++) rows.add(List.of(labels.get(i).asString(""), values.path(i).isNumber() ? values.get(i).decimalValue() : values.path(i).asString("")));
            return new DataRoom.Result(List.of("etiqueta", "valor"), rows, false);
        }
        List<String> columns = new ArrayList<>();
        b.path("columns").forEach(c -> columns.add(c.asString("")));
        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode row : b.path("rows")) {
            List<Object> values = new ArrayList<>();
            row.forEach(v -> values.add(v.isNull() ? null : v.isNumber() ? v.decimalValue() : v.asString("")));
            rows.add(values);
        }
        if (columns.isEmpty()) throw new IllegalArgumentException("pasa sql, o columns y rows (o labels y values).");
        return new DataRoom.Result(columns, rows, false);
    }

    private static JsonNode items(JsonNode b) {
        JsonNode items = b.path("items");
        if (!items.isArray() || items.isEmpty()) throw new IllegalArgumentException("falta items.");
        return items;
    }

    private static String required(JsonNode b, String field) {
        String v = b.path(field).asString("");
        if (v.isBlank()) throw new IllegalArgumentException("falta " + field + ".");
        return v;
    }

    private static Paragraph spacer(float h) {
        Paragraph p = new Paragraph(" ", SMALL);
        p.setLeading(h);
        return p;
    }

    private static BigDecimal number(Object v) {
        if (v instanceof BigDecimal n) return n;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 1234567.891 → «1,234,567.89»: separadores para leer rápido, hasta dos decimales. */
    static String format(BigDecimal n) {
        DecimalFormat f = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        return f.format(n);
    }

    private static final class PageNumbers extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT, new Phrase(String.valueOf(writer.getPageNumber()), SMALL),
                    document.right(), document.bottom() - 24, 0);
        }
    }
}
