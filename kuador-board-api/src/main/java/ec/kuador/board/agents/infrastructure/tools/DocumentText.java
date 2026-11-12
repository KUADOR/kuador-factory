package ec.kuador.board.agents.infrastructure.tools;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/** El texto de un adjunto (PDF, Word, texto plano), para que el agente lo lea. */
final class DocumentText {

    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "markdown", "json", "xml", "html", "htm", "yaml", "yml", "log");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "heic", "bmp", "tif", "tiff");

    /** {@code text} nulo si no se pudo leer; {@code note} explica qué es o por qué no se leyó. */
    record Extracted(String kind, String text, String note) {}

    /** Páginas que se leen de un PDF: más que eso es un libro, no un adjunto de trabajo. */
    static final int MAX_PAGES = 500;

    private DocumentText() {}

    static Extracted extract(String name, String contentType, byte[] content) {
        String ext = extension(name);
        try {
            if (ext.equals("pdf") || "application/pdf".equals(contentType)) return pdf(content);
            if (ext.equals("docx")) return new Extracted("Word", docx(content), null);
            if (ext.equals("doc") || ext.equals("xls") || ext.equals("ppt")) {
                return new Extracted("formato antiguo de Office", null, "No puedo leer el formato antiguo ." + ext + ": pide que lo guarden como ."
                        + ext + "x o PDF.");
            }
            if (TEXT_EXTENSIONS.contains(ext) || (contentType != null && contentType.startsWith("text/"))) {
                return new Extracted("texto", new String(content, StandardCharsets.UTF_8), null);
            }
            if (IMAGE_EXTENSIONS.contains(ext) || (contentType != null && contentType.startsWith("image/"))) {
                return new Extracted("imagen", null, "Es una imagen: no puedo ver su contenido con este proveedor de IA.");
            }
            return new Extracted("archivo ." + ext, null, "No sé leer este tipo de archivo.");
        } catch (IOException | RuntimeException e) {
            return new Extracted("archivo ." + ext, null, "No se pudo leer (¿dañado o protegido con clave?): " + e.getMessage());
        }
    }

    private static Extracted pdf(byte[] content) throws IOException {
        try (PDDocument doc = Loader.loadPDF(content)) {
            if (doc.isEncrypted() && !doc.getCurrentAccessPermission().canExtractContent()) {
                return new Extracted("PDF", null, "El PDF está protegido y no permite extraer su texto.");
            }
            int pages = Math.min(doc.getNumberOfPages(), MAX_PAGES);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            StringBuilder text = new StringBuilder();
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                text.append("--- Página ").append(p).append(" ---\n").append(stripper.getText(doc).strip()).append("\n\n");
            }
            String kind = "PDF, " + pages + (pages == 1 ? " página" : " páginas");
            long letters = text.chars().filter(Character::isLetterOrDigit).count();
            if (letters < 30L * pages) {
                return new Extracted(kind, letters == 0 ? null : text.toString(),
                        "Casi no tiene texto: parece escaneado (fotos de páginas) y este proveedor de IA no puede verlo.");
            }
            return new Extracted(kind, text.toString(), null);
        }
    }

    /** El texto de un .docx: párrafos, tabulaciones y celdas de tablas (sin formato). */
    private static String docx(byte[] content) throws IOException {
        ZipGuard.check(content);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (!e.getName().equals("word/document.xml")) continue;
                String xml = new String(zip.readNBytes(50 * 1024 * 1024), StandardCharsets.UTF_8);
                String text = xml.replaceAll("</w:p>", "\n").replaceAll("<w:tab/>", "\t").replaceAll("</w:tc>", " | ").replaceAll("<w:br/>", "\n")
                        .replaceAll("<[^>]+>", "");
                return unescape(text).replaceAll("\n{3,}", "\n\n").strip();
            }
        }
        throw new IOException("no es un documento de Word válido");
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
