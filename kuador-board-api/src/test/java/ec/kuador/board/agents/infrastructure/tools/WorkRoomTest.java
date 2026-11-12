package ec.kuador.board.agents.infrastructure.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.agents.domain.BoardAgentModel.InputFile;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Las herramientas de un agente sin ejecución de código: leer adjuntos, consultar planillas y crear documentos. */
public class WorkRoomTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Un Excel como los que exporta un sistema de ventas: título arriba, encabezados, fechas con formato y montos. */
    public static byte[] salesExcel() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook wb = new Workbook(out, "prueba", "1.0")) {
            Worksheet ws = wb.newWorksheet("Octubre");
            ws.value(0, 0, "Reporte de ventas - Florería");
            ws.value(2, 0, "Fecha");
            ws.value(2, 1, "Producto");
            ws.value(2, 2, "Cantidad");
            ws.value(2, 3, "Total $");
            Object[][] rows = {{LocalDate.of(2026, 10, 1), "Ramo de rosas", 2, 45.50}, {LocalDate.of(2026, 10, 1), "Girasoles", 1, 18.00},
                    {LocalDate.of(2026, 10, 2), "Ramo de rosas", 3, 68.25}, {LocalDate.of(2026, 10, 3), "Orquídea", 1, 32.10}};
            for (int i = 0; i < rows.length; i++) {
                ws.value(3 + i, 0, (LocalDate) rows[i][0]);
                ws.style(3 + i, 0).format("dd/mm/yyyy").set();
                ws.value(3 + i, 1, (String) rows[i][1]);
                ws.value(3 + i, 2, (Integer) rows[i][2]);
                ws.value(3 + i, 3, (Double) rows[i][3]);
            }
        }
        return out.toByteArray();
    }

    static byte[] pdf(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.lowagie.text.Document doc = new com.lowagie.text.Document();
        com.lowagie.text.pdf.PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new com.lowagie.text.Paragraph(text));
        doc.close();
        return out.toByteArray();
    }

    private static JsonNode args(String json) {
        return JSON.readTree(json);
    }

    @Test
    void loadsAnExportedExcelAsATableAndAnswersExactly() throws Exception {
        try (WorkRoom room = new WorkRoom(List.of(new InputFile("Ventas octubre.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", salesExcel())))) {
            assertThat(room.overview()).contains("Tabla ventas_octubre", "4 filas", "fecha DATE", "producto TEXT", "total NUMBER", "| 2026-10-01 | Girasoles | 1 | 18 |");
            assertThat(room.tools()).extracting(WorkRoom.ToolSpec::name).contains("read_file", "query_data", "create_csv", "create_document");

            String total = room.call("query_data", args("{\"sql\":\"select sum(total) as vendido, count(*) as ventas from ventas_octubre\"}"));
            assertThat(total).contains("| 163.85 | 4 |");
            String byDay = room.call("query_data", args("{\"sql\":\"select fecha, sum(total) t from ventas_octubre group by fecha order by fecha;\"}"));
            assertThat(byDay).contains("| 2026-10-01 | 63.5 |", "| 2026-10-03 | 32.1 |");
        }
    }

    @Test
    void queriesCannotTouchFilesOrRunCodeOrWrite() throws Exception {
        try (WorkRoom room = new WorkRoom(List.of(new InputFile("v.xlsx", null, salesExcel())))) {
            for (String sql : List.of("select file_read('/etc/hosts')", "select * from csvread('/etc/hosts')",
                    "with x as (select 1) select * from csvread('/etc/passwd')", "create alias x for 'java.lang.System.exit'",
                    "drop table v", "select 1; drop table v", "call link_schema('a', '', 'jdbc:h2:mem:x', 'sa', '', 'PUBLIC')")) {
                String r = room.call("query_data", JSON.createObjectNode().put("sql", sql));
                assertThat(r).as(sql).startsWith("Error:");
            }
            assertThat(room.call("query_data", args("{\"sql\":\"select count(*) from v\"}"))).contains("| 4 |");
            // Solo se ve a sí mismo: ningún usuario con permisos de administración.
            assertThat(room.call("query_data", args("{\"sql\":\"select user_name, is_admin from information_schema.users\"}"))).contains("| LECTOR | false |").doesNotContain("true");
        }
    }

    @Test
    void readsCsvAndPdfAndExplainsWhatItCannotRead() throws Exception {
        String csv = "fecha;producto;total\n01/10/2026;Rosas;1.234,50\n02/10/2026;\"Girasol; grande\";10\n";
        try (WorkRoom room = new WorkRoom(List.of(new InputFile("ventas.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)),
                new InputFile("contrato.pdf", "application/pdf", pdf("Contrato de provisión de flores por 12 meses. Total anual: 8.400 dólares.")),
                new InputFile("foto.jpg", "image/jpeg", new byte[] {1, 2, 3}), new InputFile("viejo.xls", null, new byte[] {1})))) {
            assertThat(room.call("query_data", args("{\"sql\":\"select sum(total) from ventas\"}"))).contains("| 1244.5 |");
            assertThat(room.call("read_file", args("{\"name\":\"contrato.pdf\"}"))).contains("Página 1", "Total anual: 8.400 dólares");
            assertThat(room.overview()).contains("«foto.jpg» (imagen)", "no puedo ver", "viejo.xls", "guarden como .xlsx");
            assertThat(room.call("read_file", args("{\"name\":\"nada.pdf\"}"))).startsWith("Error:").contains("contrato.pdf");
        }
    }

    @Test
    void createsADashboardPdfWithExactFiguresFromTheData() throws Exception {
        try (WorkRoom room = new WorkRoom(List.of(new InputFile("ventas.xlsx", null, salesExcel())))) {
            String r = room.call("create_document", args("""
                    {"file_name": "Resumen octubre", "title": "Ventas de octubre", "subtitle": "Del 1 al 3 de octubre de 2026",
                     "blocks": [
                       {"type": "kpis", "items": [{"label": "Vendido", "value": "$163.85"}, {"label": "Ventas", "value": "4", "note": "3 días"}]},
                       {"type": "heading", "text": "Por producto"},
                       {"type": "table", "sql": "select producto, sum(total) as total from ventas group by producto order by 2 desc"},
                       {"type": "bar_chart", "title": "Ventas por día", "sql": "select fecha, sum(total) from ventas group by fecha order by fecha"},
                       {"type": "line_chart", "title": "Tendencia", "labels": ["1", "2", "3"], "values": [63.5, 68.25, 32.1]},
                       {"type": "paragraph", "text": "Las **rosas** explican el 70% de lo vendido."},
                       {"type": "bullets", "items": ["Reforzar rosas", "Promocionar orquídeas"]}
                     ]}"""));
            assertThat(r).startsWith("Listo: Resumen octubre.pdf");
            byte[] pdf = room.outputs().getFirst().content();
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                String text = new PDFTextStripper().getText(doc);
                assertThat(text).contains("Ventas de octubre", "$163.85", "Ramo de rosas", "113.75", "Ventas por día", "Reforzar rosas");
            }
            assertThat(room.call("create_document", args("{\"file_name\":\"x.pdf\",\"title\":\"X\",\"blocks\":[{\"type\":\"table\",\"sql\":\"select nada from ventas\"}]}")))
                    .startsWith("Error: Bloque 1 (table): La consulta falló");
            assertThat(room.call("create_csv", args("{\"file_name\":\"detalle\",\"sql\":\"select producto, total from ventas order by total desc\"}"))).contains("detalle.csv");
            assertThat(new String(room.outputs().get(1).content(), StandardCharsets.UTF_8)).startsWith("producto,total\r\nRamo de rosas,68.25");
        }
    }

    @Test
    void parsesCommonNumberAndDateFormats() {
        assertThat(SheetReader.parseText("1.234,56")).isEqualTo(new BigDecimal("1234.56"));
        assertThat(SheetReader.parseText("1,234.56")).isEqualTo(new BigDecimal("1234.56"));
        assertThat(SheetReader.parseText("$ 20")).isEqualTo(new BigDecimal("20"));
        assertThat(SheetReader.parseText("12,5")).isEqualTo(new BigDecimal("12.5"));
        assertThat(SheetReader.parseText("0991234567")).isEqualTo("0991234567");
        assertThat(SheetReader.parseText("31/10/2026")).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(SheetReader.identifier("Año", "x")).isEqualTo("ano");
        assertThat(SheetReader.identifier("Order", "x")).isEqualTo("order_");
    }
}
