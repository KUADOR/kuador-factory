package ec.kuador.board.connections.infrastructure.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/** Un adjunto se lee igual venga de donde venga: como recurso, o en base64 dentro del JSON de cualquier proveedor de correo. */
class ToolFilesTest {

    static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<factura><infoTributaria><razonSocial>Flores del Valle S.A.</razonSocial>"
            + "<secuencial>000012345</secuencial></infoTributaria><infoFactura><fechaEmision>20/11/2026</fechaEmision><importeTotal>1840.50</importeTotal>"
            + "</infoFactura><detalles><detalle><descripcion>Cajas de cartón para exportación</descripcion><cantidad>500</cantidad>"
            + "<precioUnitario>3.20</precioUnitario></detalle></detalles></factura>";

    static byte[] pdf(String... lines) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                c.beginText();
                c.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                c.setLeading(16);
                c.newLineAtOffset(50, 700);
                for (String l : lines) {
                    c.showText(l);
                    c.newLine();
                }
                c.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void readsAPdfInvoiceWhateverTheServerCallsIt() throws Exception {
        byte[] invoice = pdf("Factura 001-001-000012345", "Proveedor: Flores del Valle S.A.", "Total: 1840.50 USD");

        assertThat(ToolFiles.read("factura.pdf", "application/pdf", invoice)).startsWith("[Archivo «factura.pdf» (PDF, 1 página)]")
                .contains("Factura 001-001-000012345", "Total: 1840.50 USD");
        assertThat(ToolFiles.read("adjunto", "application/octet-stream", invoice)).as("sin extensión ni tipo: se reconoce por su contenido")
                .contains("Proveedor: Flores del Valle S.A.");
    }

    @Test
    void readsAnElectronicInvoiceInXmlEvenWhenSentAsBinary() {
        assertThat(ToolFiles.read("factura.xml", "application/octet-stream", XML.getBytes(StandardCharsets.UTF_8)))
                .startsWith("[Archivo «factura.xml» (texto)]").contains("<importeTotal>1840.50</importeTotal>");
    }

    @Test
    void saysWhyItCannotReadAPhotoOrASpreadsheet() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0};
        assertThat(ToolFiles.read("foto.png", "image/png", png)).contains("es una imagen", "pide a una persona");
        assertThat(ToolFiles.read("pedidos.xlsx", null, new byte[] {'P', 'K', 3, 4, 0, 0})).contains("planilla de Excel");
    }

    @Test
    void unwrapsAttachmentsTheWayMailProvidersSendThem() throws Exception {
        byte[] invoice = pdf("Factura 001-001-000012345", "Total: 1840.50 USD");

        // Microsoft Graph (Outlook): contentBytes en base64 con name y contentType.
        String graph = "{\"value\":[{\"id\":\"AAMk\",\"name\":\"factura.pdf\",\"contentType\":\"application/pdf\",\"size\":" + invoice.length
                + ",\"contentBytes\":\"" + Base64.getEncoder().encodeToString(invoice) + "\"}]}";
        assertThat(ToolFiles.unwrap(graph)).contains("[Archivo «factura.pdf» (PDF, 1 página)]", "Total: 1840.50 USD", "\"id\":\"AAMk\"").doesNotContain("JVBERi0");

        // Gmail: data en base64 para direcciones web, con filename y mimeType.
        String gmail = "{\"filename\":\"factura.xml\",\"mimeType\":\"application/xml\",\"data\":\""
                + Base64.getUrlEncoder().withoutPadding().encodeToString(XML.getBytes(StandardCharsets.UTF_8)) + "\"}";
        assertThat(ToolFiles.unwrap(gmail)).contains("Flores del Valle S.A.", "importeTotal");

        // El cuerpo del correo en base64, sin nombre: se entrega como texto corrido.
        String body = "Estimados, adjunto la factura del pedido 4471 por las cajas de exportación despachadas el 20 de noviembre. ".repeat(3);
        String message = "{\"subject\":\"Factura pedido 4471\",\"body\":{\"data\":\"" + Base64.getUrlEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)) + "\"}}";
        assertThat(ToolFiles.unwrap(message)).contains("adjunto la factura del pedido 4471").doesNotContain("[Archivo");
    }

    @Test
    void leavesEverythingElseAsItCame() {
        String plain = "3 correos nuevos. El primero es de proveedores@flores.example.";
        assertThat(ToolFiles.unwrap(plain)).isSameAs(plain);
        String json = "{\"id\":\"" + "a1b2c3d4".repeat(40) + "\",\"total\":3,\"nota\":\"" + "sin archivos aquí, solo un texto largo con espacios. ".repeat(8) + "\"}";
        assertThat(ToolFiles.unwrap(json)).as("un identificador largo o un texto no son archivos").isSameAs(json);
    }
}
