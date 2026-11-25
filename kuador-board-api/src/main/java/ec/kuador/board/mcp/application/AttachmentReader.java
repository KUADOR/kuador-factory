package ec.kuador.board.mcp.application;

import ec.kuador.board.attachment.AttachmentApi;
import ec.kuador.board.issue.IssueApi.IssueContext;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Lee un adjunto de un issue para el agente: texto tal cual, el texto de un PDF, o una imagen que ve directamente. */
class AttachmentReader {

    private final ToolScope scope;
    private final AttachmentApi attachments;

    AttachmentReader(ToolScope scope, AttachmentApi attachments) {
        this.scope = scope;
        this.attachments = attachments;
    }

    Object read(Map<String, String> a) {
        IssueContext c = scope.context(a);
        java.util.UUID id;
        try {
            id = java.util.UUID.fromString(ToolScope.required(a, "id"));
        } catch (IllegalArgumentException e) {
            throw new ToolException("El id del adjunto no es válido; tómalo de get_issue.");
        }
        AttachmentApi.AttachmentInfo info = attachments.list(c.key()).stream().filter(f -> f.id().equals(id)).findFirst()
                .orElseThrow(() -> new ToolException("No existe ese adjunto en " + c.key() + "."));
        String name = info.fileName().toLowerCase(Locale.ROOT);
        String type = info.contentType() == null ? "" : info.contentType().toLowerCase(Locale.ROOT);
        Map<String, Object> meta = new LinkedHashMap<>(Map.of("key", c.key(), "id", id.toString(), "name", info.fileName()));
        Optional<String> text = attachments.readText(c.key(), id, MAX_TEXT);
        if (text.isPresent()) {
            meta.put("content", text.get());
            return meta;
        }
        boolean pdf = type.equals("application/pdf") || name.endsWith(".pdf");
        String image = IMAGE_TYPES.getOrDefault(type, IMAGE_TYPES.get(name.replaceAll("^.*\\.", "image/").replace("jpg", "jpeg")));
        if (!pdf && image == null) {
            throw new ToolException("Ese adjunto (" + info.contentType() + ") no se puede leer desde aquí: pídele a una persona que lo resuma o lo suba como PDF o imagen.");
        }
        if (info.sizeBytes() > (pdf ? MAX_PDF : MAX_IMAGE)) {
            throw new ToolException("Ese adjunto pesa demasiado para leerlo aquí (" + info.sizeBytes() / 1024 + " KB): pídele a una persona un resumen.");
        }
        AttachmentApi.FileContent file = attachments.content(c.key(), id)
                .orElseThrow(() -> new ToolException("No se pudo descargar ese adjunto: el almacenamiento no está disponible."));
        if (image != null) return new ToolImage(image, java.util.Base64.getEncoder().encodeToString(file.content()), meta);
        meta.put("content", pdfText(file.content()));
        return meta;
    }

    private static final int MAX_TEXT = 200 * 1024;
    private static final long MAX_IMAGE = 5L * 1024 * 1024;
    private static final long MAX_PDF = 20L * 1024 * 1024;
    private static final Map<String, String> IMAGE_TYPES = Map.of("image/png", "image/png", "image/jpeg", "image/jpeg", "image/gif", "image/gif",
            "image/webp", "image/webp");

    /** El texto de un PDF, página por página (hasta 200 KB). */
    private static String pdfText(byte[] content) {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(content)) {
            if (doc.isEncrypted() && !doc.getCurrentAccessPermission().canExtractContent()) {
                throw new ToolException("El PDF está protegido y no permite extraer su texto.");
            }
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc).strip();
            if (text.isEmpty()) throw new ToolException("El PDF no tiene texto (parece escaneado): pídele a una persona que lo suba como imagen o lo resuma.");
            return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "\n\n[… recortado: el PDF sigue]" : text;
        } catch (java.io.IOException e) {
            throw new ToolException("No se pudo leer el PDF (¿dañado?): " + e.getMessage());
        }
    }
}
