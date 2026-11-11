package ec.kuador.board.sync.infrastructure.web;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.docs.DocsApi.DocContent;
import ec.kuador.board.sync.application.SyncService;
import jakarta.validation.constraints.NotNull;
import ec.kuador.board.sync.application.ComponentService;
import ec.kuador.board.sync.domain.ProjectComponent;
import java.util.Map;
import ec.kuador.board.sync.domain.RepositoryLink;
import ec.kuador.board.sync.domain.RepositoryProvider;
import ec.kuador.board.sync.domain.SyncState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Solo personas (reglas en SecurityConfiguration): aquí se lee y escribe en el disco del servidor. */
@RestController
@RequestMapping("/api/projects/{projectKey}")
class SyncController {

    private final SyncService service;
    private final ComponentService components;

    SyncController(SyncService service, ComponentService components) {
        this.components = components;
        this.service = service;
    }

    @GetMapping("/repository")
    LinkResponse link(@PathVariable String projectKey) {
        return service.link(projectKey).map(LinkResponse::from).orElse(LinkResponse.NONE);
    }

    @PutMapping("/repository")
    LinkResponse link(
            @PathVariable String projectKey, @Valid @RequestBody LinkRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        return LinkResponse.from(service.link(projectKey, r.kind(), new RepositoryProvider.LinkSpec(r.location(), r.branch(), r.token(), r.installationId()),
                who.memberId()));
    }

    // ---- Piezas del proyecto (API, web, microservicios...) y sus repos ----

    record PieceRequest(@NotBlank @Size(max = 40) String name, @Size(max = 40) String templateKey, @Size(max = 100) String repoName,
            Map<String, String> variables) {
        ComponentService.PieceSpec spec() {
            return new ComponentService.PieceSpec(name, templateKey, repoName, variables);
        }
    }

    record SetupRequest(@NotNull Long installationId, @NotBlank @Size(max = 100) String repoName, @Size(max = 350) String description,
            /** Privado si no se indica: el código de un cliente no se publica por accidente. */
            Boolean privateRepo, @Valid List<PieceRequest> pieces) {}

    record AddPieceRequest(@NotNull @Valid PieceRequest piece, Boolean privateRepo) {}

    record PieceResponse(String name, String templateKey, Long templateVersion, String templateRef, String kind, String stack, String repository,
            String path, String build, String test, String run, Instant createdAt) {
        static PieceResponse from(ProjectComponent c) {
            return new PieceResponse(c.name(), c.templateKey(), c.templateVersion(), c.templateRef(), c.kind(), c.stack(), c.repoLocation(), c.path(),
                    c.buildCommand(), c.testCommand(), c.runCommand(), c.createdAt());
        }
    }

    record SetupResponse(LinkResponse link, List<ComponentService.RepoResult> repositories, List<PieceResponse> pieces, int documents) {
        static SetupResponse from(ComponentService.Result r) {
            return new SetupResponse(LinkResponse.from(r.link()), r.repositories(), r.pieces().stream().map(PieceResponse::from).toList(), r.documents());
        }
    }

    @GetMapping("/components")
    List<PieceResponse> pieces(@PathVariable String projectKey) {
        return components.list(projectKey).stream().map(PieceResponse::from).toList();
    }

    /** Arma el proyecto en GitHub: repo del proyecto con sus bases y documentos, y sus piezas (carpetas o repos propios). */
    @PostMapping("/components/setup")
    @ResponseStatus(HttpStatus.CREATED)
    SetupResponse setup(@PathVariable String projectKey, @Valid @RequestBody SetupRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        List<ComponentService.PieceSpec> pieces = r.pieces() == null ? List.of() : r.pieces().stream().map(PieceRequest::spec).toList();
        return SetupResponse.from(components.setup(projectKey,
                new ComponentService.Setup(r.installationId(), r.repoName(), r.description(), r.privateRepo() == null || r.privateRepo(), pieces), who.memberId()));
    }

    /** Suma una pieza: carpeta nueva por Pull Request a develop, o un repo propio con sus bases. */
    @PostMapping("/components")
    @ResponseStatus(HttpStatus.CREATED)
    SetupResponse addPiece(@PathVariable String projectKey, @Valid @RequestBody AddPieceRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        return SetupResponse.from(components.add(projectKey, r.piece().spec(), r.privateRepo() == null || r.privateRepo(), who.memberId()));
    }

    @DeleteMapping("/repository")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlink(@PathVariable String projectKey) {
        service.unlink(projectKey);
    }

    @GetMapping("/sync")
    StatusResponse status(@PathVariable String projectKey, @AuthenticationPrincipal AuthenticatedMember who) {
        SyncService.Status s = service.status(projectKey, who.memberId());
        return new StatusResponse(LinkResponse.from(s.link()), s.entries().stream().map(EntryResponse::from).toList(),
                s.untracked().stream().map(u -> new UntrackedResponse(u.group(), u.issueScoped(),
                        u.files().stream().map(f -> new UntrackedFileResponse(f.path(), f.kind())).toList(), u.suggestedTitle())).toList(),
                s.openPullRequests().stream().map(p -> new PullRequestResponse(p.number(), p.url(), p.items().size())).toList());
    }

    @GetMapping("/sync/docs/{documentId}/compare")
    CompareResponse compare(@PathVariable String projectKey, @PathVariable UUID documentId) {
        SyncService.Comparison c = service.compare(projectKey, documentId);
        return new CompareResponse(DocResponse.from(c.document()), c.document().content(), c.fileContent(), c.state());
    }

    @PostMapping("/sync/docs/{documentId}/export")
    SyncService.Exported export(
            @PathVariable String projectKey, @PathVariable UUID documentId, @RequestBody(required = false) ForceRequest r,
            @AuthenticationPrincipal AuthenticatedMember who) {
        return service.export(projectKey, documentId, r != null && r.force(), who.memberId());
    }

    /** Varios documentos en un solo commit / PR. {@code forced}: conflictos donde se eligió el tablero. */
    @PostMapping("/sync/export")
    SyncService.Exported exportMany(
            @PathVariable String projectKey, @Valid @RequestBody ExportRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        return service.export(projectKey, r.documentIds(), r.forced() == null ? Set.of() : r.forced(), who.memberId());
    }

    @PostMapping("/sync/docs/{documentId}/import")
    EntryResponse importFile(
            @PathVariable String projectKey, @PathVariable UUID documentId, @RequestBody(required = false) ForceRequest r,
            @AuthenticationPrincipal AuthenticatedMember who) {
        return EntryResponse.from(service.importFile(projectKey, documentId, r != null && r.force(), who.memberId()));
    }

    @PostMapping("/sync/import-new")
    List<EntryResponse> importNew(
            @PathVariable String projectKey, @Valid @RequestBody ImportNewRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        return service.importNew(projectKey, r.group(), r.issueKey(), r.newIssueTitle(), who.memberId()).stream()
                .map(EntryResponse::from).toList();
    }

    @PostMapping("/sync/safe")
    SyncService.SafeResult syncSafe(@PathVariable String projectKey, @AuthenticationPrincipal AuthenticatedMember who) {
        return service.syncSafe(projectKey, who.memberId());
    }

    /** {@code token} solo para GitHub; nunca se devuelve. {@code branch} vacío = rama por defecto del repo. */
    record LinkRequest(
            @NotBlank @Pattern(regexp = "LOCAL|GITHUB|GITHUB_APP") String kind,
            @NotBlank @Size(max = 1000) String location,
            @Size(max = 255) String branch,
            @Size(max = 255) String token,
            /** Solo GITHUB_APP: la conexión de GitHub del espacio. */
            Long installationId) {}

    record ExportRequest(@NotEmpty List<UUID> documentIds, Set<UUID> forced) {}

    /** {@code force}: una persona eligió este lado en un conflicto. */
    record ForceRequest(boolean force) {}

    record ImportNewRequest(@NotBlank @Size(max = 300) String group, @Size(max = 20) String issueKey, @Size(max = 200) String newIssueTitle) {}

    /** Sin el secreto: el token no sale de la API. */
    /** {@code installationId}: la conexión de GitHub (solo GITHUB_APP; no es un secreto). */
    record LinkResponse(boolean linked, String kind, String location, String branch, Long installationId, Instant linkedAt, UUID linkedBy) {
        static final LinkResponse NONE = new LinkResponse(false, null, null, null, null, null, null);

        static LinkResponse from(RepositoryLink l) {
            return new LinkResponse(true, l.kind(), l.location(), l.branch(), "GITHUB_APP".equals(l.kind()) ? Long.valueOf(l.secret()) : null,
                    l.linkedAt(), l.linkedBy());
        }
    }

    record DocResponse(UUID id, String issueKey, String kind, String title, String path, int version) {
        static DocResponse from(DocContent d) {
            return new DocResponse(d.id(), d.issueKey(), d.kind(), d.title(), d.path(), d.version());
        }
    }

    record EntryResponse(DocResponse document, SyncState state, boolean fileExists, SyncService.OpenPr openPr) {
        static EntryResponse from(SyncService.Entry e) {
            return new EntryResponse(DocResponse.from(e.document()), e.state(), e.fileExists(), e.openPr());
        }
    }

    record PullRequestResponse(int number, String url, int documents) {}

    record UntrackedFileResponse(String path, String kind) {}

    record UntrackedResponse(String group, boolean issueScoped, List<UntrackedFileResponse> files, String suggestedTitle) {}

    record StatusResponse(
            LinkResponse link, List<EntryResponse> entries, List<UntrackedResponse> untracked, List<PullRequestResponse> openPullRequests) {}

    record CompareResponse(DocResponse document, String boardContent, String fileContent, SyncState state) {}
}
