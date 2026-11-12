package ec.kuador.board.attachment.infrastructure.web;

import ec.kuador.board.attachment.AttachmentApi;
import ec.kuador.board.attachment.application.AttachmentService;
import ec.kuador.board.attachment.domain.Attachment;
import ec.kuador.board.auth.AuthenticatedMember;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Adjuntos de una tarjeta. La descarga sale por aquí (con la sesión), nunca directo del almacenamiento. */
@RestController
class AttachmentController {

    /** Tipos que el navegador puede mostrar sin riesgo dentro de la página; el resto siempre se descarga. */
    private static final List<String> INLINE = List.of("image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf");

    private final AttachmentService attachments;

    AttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    record Listing(boolean available, long maxBytes, List<AttachmentApi.AttachmentInfo> items) {}

    @GetMapping("/api/issues/{key}/attachments")
    Listing list(@PathVariable String key) {
        return new Listing(attachments.available(), attachments.maxBytes(), attachments.available() ? attachments.list(key) : List.of());
    }

    @PostMapping(path = "/api/issues/{key}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    AttachmentApi.AttachmentInfo upload(@AuthenticationPrincipal AuthenticatedMember who, @PathVariable String key, @RequestParam("file") MultipartFile file)
            throws IOException {
        try (InputStream in = file.getInputStream()) {
            Attachment a = attachments.upload(key, file.getOriginalFilename(), file.getContentType(), file.getSize(), in, who.memberId());
            return new AttachmentApi.AttachmentInfo(a.id(), a.issueKey(), a.fileName(), a.contentType(), a.sizeBytes(), a.uploadedBy(), a.createdAt());
        }
    }

    @GetMapping("/api/attachments/{id}/content")
    ResponseEntity<InputStreamResource> content(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean inline) {
        AttachmentService.Download d = attachments.open(id);
        Attachment a = d.attachment();
        boolean showInline = inline && INLINE.contains(a.contentType());
        ContentDisposition disposition = (showInline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(a.fileName(), java.nio.charset.StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .contentType(showInline ? MediaType.parseMediaType(a.contentType()) : MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(a.sizeBytes())
                .body(new InputStreamResource(d.content()));
    }

    @DeleteMapping("/api/attachments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        attachments.delete(id);
    }
}
