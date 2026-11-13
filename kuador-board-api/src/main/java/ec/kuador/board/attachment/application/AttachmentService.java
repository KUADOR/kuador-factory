package ec.kuador.board.attachment.application;

import ec.kuador.board.attachment.AttachmentApi;
import ec.kuador.board.attachment.domain.Attachment;
import ec.kuador.board.attachment.domain.AttachmentStore;
import ec.kuador.board.attachment.domain.ObjectStorage;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.issue.IssueDeleted;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

/** Subir, listar, descargar y borrar adjuntos de una tarjeta. Al borrar la tarjeta se borran sus archivos. */
@Service
public class AttachmentService implements AttachmentApi {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final AttachmentStore store;
    private final ObjectStorage storage;
    private final IssueApi issues;
    private final Clock clock;
    private final long maxBytes;

    AttachmentService(AttachmentStore store, ObjectStorage storage, IssueApi issues, Clock clock,
            @Value("${board.storage.max-file-size:25MB}") DataSize maxFileSize) {
        this.store = store;
        this.storage = storage;
        this.issues = issues;
        this.clock = clock;
        this.maxBytes = maxFileSize.toBytes();
    }

    /** Un archivo abierto para descargar; quien lo recibe cierra {@code content}. */
    public record Download(Attachment attachment, InputStream content) {}

    @Override
    public boolean available() {
        return storage.available();
    }

    public long maxBytes() {
        return maxBytes;
    }

    @Transactional
    public Attachment upload(String issueKey, String fileName, String contentType, long size, InputStream content, UUID actorId) {
        requireStorage();
        issues.find(issueKey).orElseThrow(() -> new NotFoundException("No existe el issue " + issueKey + "."));
        Attachment.checkSize(size, maxBytes);
        Attachment a = Attachment.create(TenantContext.require(), issueKey, fileName, contentType, size, actorId, clock.instant());
        storage.put(a.storageKey(), a.contentType(), content, size);
        try {
            store.save(a);
        } catch (RuntimeException e) {
            forget(a.storageKey());
            throw e;
        }
        return a;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttachmentInfo> list(String issueKey) {
        return store.byIssue(TenantContext.require(), issueKey).stream().map(AttachmentService::info).toList();
    }

    @Transactional(readOnly = true)
    public Download open(UUID id) {
        requireStorage();
        Attachment a = find(id);
        return new Download(a, storage.get(a.storageKey()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> readText(String issueKey, UUID id, int maxBytes) {
        Attachment a = find(id);
        if (!a.issueKey().equals(issueKey) || !a.textual() || a.sizeBytes() > maxBytes || !storage.available()) return Optional.empty();
        try (InputStream in = storage.get(a.storageKey())) {
            return Optional.of(new String(in.readNBytes(maxBytes), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DomainException("No se pudo leer el adjunto: " + e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FileContent> content(String issueKey, UUID id) {
        if (!storage.available()) return Optional.empty();
        Optional<Attachment> found = store.find(TenantContext.require(), id).filter(a -> a.issueKey().equals(issueKey));
        if (found.isEmpty()) return Optional.empty();
        Attachment a = found.get();
        try (InputStream in = storage.get(a.storageKey())) {
            return Optional.of(new FileContent(a.fileName(), a.contentType(), in.readAllBytes()));
        } catch (NotFoundException e) {
            return Optional.empty(); // el archivo ya no está en el almacenamiento
        } catch (IOException e) {
            throw new DomainException("No se pudo leer el adjunto " + a.fileName() + ": " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public AttachmentInfo attach(String issueKey, String fileName, String contentType, byte[] content, UUID actorId) {
        return info(upload(issueKey, fileName, contentType, content.length, new java.io.ByteArrayInputStream(content), actorId));
    }

    @Transactional
    public void delete(UUID id) {
        Attachment a = find(id);
        store.delete(a.workspaceId(), a.id());
        forget(a.storageKey());
    }

    /** La tarjeta se borró: sus archivos también. */
    @ApplicationModuleListener
    void on(IssueDeleted e) {
        TenantContext.run(e.workspaceId(), () -> store.byIssue(e.workspaceId(), e.issueKey()).forEach(a -> {
            store.delete(a.workspaceId(), a.id());
            forget(a.storageKey());
        }));
    }

    /** Se borró un tablero: los adjuntos de todas sus tarjetas, con sus archivos. */
    @ApplicationModuleListener
    void on(ec.kuador.board.project.ProjectDeleted e) {
        TenantContext.run(e.workspaceId(), () -> store.byProject(e.workspaceId(), e.projectKey()).forEach(a -> {
            store.delete(a.workspaceId(), a.id());
            forget(a.storageKey());
        }));
    }

    private Attachment find(UUID id) {
        return store.find(TenantContext.require(), id).orElseThrow(() -> new NotFoundException("No existe ese adjunto."));
    }

    private void requireStorage() {
        if (!storage.available()) {
            throw new DomainException("Los adjuntos todavía no están habilitados en este servidor (falta configurar el almacenamiento).");
        }
    }

    private void forget(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("No se pudo borrar el archivo {} del almacenamiento: {}", key, e.getMessage());
        }
    }

    private static AttachmentInfo info(Attachment a) {
        return new AttachmentInfo(a.id(), a.issueKey(), a.fileName(), a.contentType(), a.sizeBytes(), a.uploadedBy(), a.createdAt());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> issueOf(UUID attachmentId) {
        return store.find(TenantContext.require(), attachmentId).map(ec.kuador.board.attachment.domain.Attachment::issueKey);
    }
}
