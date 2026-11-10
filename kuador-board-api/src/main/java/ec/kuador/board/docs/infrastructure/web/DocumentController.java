package ec.kuador.board.docs.infrastructure.web;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.catalog.Methodology;
import ec.kuador.board.docs.application.DocumentService;
import ec.kuador.board.docs.domain.Document;
import ec.kuador.board.docs.domain.DocumentVersion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DocumentController {

    private final DocumentService service;

    DocumentController(DocumentService service) {
        this.service = service;
    }

    @GetMapping("/api/projects/{projectKey}/docs")
    OverviewResponse overview(@PathVariable String projectKey) {
        DocumentService.Overview o = service.overview(projectKey);
        return new OverviewResponse(o.methodology(), o.documents().stream().map(DocumentResponse::from).toList());
    }

    @PostMapping("/api/projects/{projectKey}/docs")
    @ResponseStatus(HttpStatus.CREATED)
    DocumentWithContentResponse create(
            @PathVariable String projectKey, @Valid @RequestBody CreateRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        return DocumentWithContentResponse.from(service.create(projectKey, r.kind(), r.issueKey(), who.memberId()));
    }

    @GetMapping("/api/issues/{issueKey}/docs")
    List<DocumentResponse> ofIssue(@PathVariable String issueKey) {
        return service.ofIssue(issueKey).stream().map(DocumentResponse::from).toList();
    }

    @GetMapping("/api/docs/{id}")
    DocumentWithContentResponse get(@PathVariable UUID id) {
        return DocumentWithContentResponse.from(service.get(id));
    }

    @PutMapping("/api/docs/{id}")
    DocumentWithContentResponse save(
            @PathVariable UUID id, @Valid @RequestBody SaveRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        return DocumentWithContentResponse.from(service.save(id, r.baseVersion(), r.content(), r.note(), who.memberId()));
    }

    @GetMapping("/api/docs/{id}/versions")
    List<VersionResponse> versions(@PathVariable UUID id) {
        return service.versions(id).stream().map(VersionResponse::from).toList();
    }

    @GetMapping("/api/docs/{id}/versions/{version}")
    VersionWithContentResponse version(@PathVariable UUID id, @PathVariable int version) {
        DocumentVersion v = service.version(id, version);
        return new VersionWithContentResponse(VersionResponse.from(v), v.content());
    }

    @PostMapping("/api/docs/{id}/versions/{version}/restore")
    DocumentWithContentResponse restore(
            @PathVariable UUID id, @PathVariable int version, @Valid @RequestBody RestoreRequest r,
            @AuthenticationPrincipal AuthenticatedMember who) {
        return DocumentWithContentResponse.from(service.restore(id, version, r.baseVersion(), who.memberId()));
    }

    record CreateRequest(@NotBlank @Size(max = 40) String kind, @Size(max = 20) String issueKey) {}

    record SaveRequest(@Min(1) int baseVersion, @NotNull String content, @Size(max = 200) String note) {}

    record RestoreRequest(@Min(1) int baseVersion) {}

    record DocumentResponse(
            UUID id, String projectKey, String issueKey, String kind, String title, String path, int currentVersion,
            Instant createdAt, Instant updatedAt, UUID updatedBy) {

        static DocumentResponse from(Document d) {
            return new DocumentResponse(d.id(), d.projectKey(), d.issueKey(), d.kind(), d.title(), d.path(), d.currentVersion(),
                    d.createdAt(), d.updatedAt(), d.updatedBy());
        }
    }

    record DocumentWithContentResponse(DocumentResponse document, String content) {
        static DocumentWithContentResponse from(DocumentService.WithContent w) {
            return new DocumentWithContentResponse(DocumentResponse.from(w.document()), w.content());
        }
    }

    /** {@code methodology}: definición completa del catálogo (o nula si el proyecto no eligió). */
    record OverviewResponse(Methodology methodology, List<DocumentResponse> documents) {}

    record VersionResponse(int version, UUID authorId, String note, Instant createdAt, int length) {
        static VersionResponse from(DocumentVersion v) {
            return new VersionResponse(v.version(), v.authorId(), v.note(), v.createdAt(), v.content().length());
        }
    }

    record VersionWithContentResponse(VersionResponse version, String content) {}
}
