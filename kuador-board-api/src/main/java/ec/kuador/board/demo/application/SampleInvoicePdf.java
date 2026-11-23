package ec.kuador.board.demo.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.format.DateTimeFormatter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** El PDF de una factura de ejemplo, con el formato de una factura ecuatoriana sencilla. */
public final class SampleInvoicePdf {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private SampleInvoicePdf() {}

    public static byte[] render(DemoErp.SampleInvoice inv) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                float y = 790;
                y = line(c, bold, 16, 50, y, ascii(inv.proveedor().nombre()));
                y = line(c, regular, 10, 50, y - 4, "RUC: " + inv.proveedor().ruc() + "    Correo: " + inv.proveedor().correo());
                y = line(c, bold, 14, 50, y - 18, "FACTURA No. " + inv.numero());
                y = line(c, regular, 10, 50, y - 4, "Fecha de emision: " + DATE.format(inv.fecha()));
                y = line(c, regular, 10, 50, y, "Cliente: Flores de la Sierra S.A.   RUC: 1790099887001");
                y = line(c, bold, 10, 50, y - 4, "Orden de compra: " + inv.pedido());
                y -= 16;
                y = line(c, bold, 10, 50, y, String.format("%-8s %-46s %10s %12s %12s", "Codigo", "Descripcion", "Cantidad", "P. unitario", "Subtotal"));
                for (DemoErp.Item i : inv.items()) {
                    y = line(c, regular, 9, 50, y, String.format("%-8s %-46s %10s %12s %12s", i.codigo(), ascii(clip(i.descripcion(), 46)), i.cantidad().stripTrailingZeros().toPlainString(),
                            i.precioUnitario().toPlainString(), i.subtotal().toPlainString()));
                }
                y -= 10;
                y = line(c, regular, 10, 330, y, "Subtotal 15%:   " + inv.subtotal().toPlainString());
                y = line(c, regular, 10, 330, y, "IVA 15%:        " + inv.iva().toPlainString());
                line(c, bold, 11, 330, y, "TOTAL USD:      " + inv.total().toPlainString());
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static float line(PDPageContentStream c, PDType1Font font, float size, float x, float y, String text) throws IOException {
        c.beginText();
        c.setFont(font, size);
        c.newLineAtOffset(x, y);
        c.showText(text);
        c.endText();
        return y - size - 5;
    }

    /** Las fuentes estándar de PDF no traen tildes: se escriben sin ellas. */
    private static String ascii(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace("→", "->");
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
