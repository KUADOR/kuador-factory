package ec.kuador.board.issue.infrastructure.web;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.issue.application.IssueService;
import ec.kuador.board.issue.domain.Comment;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.issue.domain.IssueDetails;
import ec.kuador.board.issue.domain.IssueFilter;
import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.issue.domain.Priority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class IssueController {

    private final IssueService service;

    IssueController(IssueService service) {
        this.service = service;
    }

    @GetMapping("/api/projects/{projectKey}/issues")
    List<IssueResponse> board(
            @PathVariable String projectKey,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID assigneeId,
            @RequestParam(required = false) IssueType type) {
        return service.board(projectKey, new IssueFilter(q, assigneeId, type)).stream().map(IssueResponse::from).toList();
    }

    @PostMapping("/api/projects/{projectKey}/issues")
    @ResponseStatus(HttpStatus.CREATED)
    IssueResponse create(
            @PathVariable String projectKey,
            @Valid @RequestBody CreateIssueRequest r,
            @AuthenticationPrincipal AuthenticatedMember who) {
        // El autor es quien está autenticado: nadie puede crear issues a nombre de otro.
        return IssueResponse.from(service.create(projectKey, r.details(), who.memberId(), r.status()));
    }

    @GetMapping("/api/issues/{key}")
    IssueResponse get(@PathVariable String key) {
        return IssueResponse.from(service.get(key));
    }

    @PutMapping("/api/issues/{key}")
    IssueResponse edit(
            @PathVariable String key, @Valid @RequestBody IssueDetailsRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        return IssueResponse.from(service.edit(key, r.toDomain(), who.memberId()));
    }

    @PutMapping("/api/issues/{key}/assignee")
    IssueResponse assign(
            @PathVariable String key, @RequestBody AssignRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        return IssueResponse.from(service.assign(key, r.assigneeId(), who.memberId()));
    }

    @PostMapping("/api/issues/{key}/move")
    IssueResponse move(
            @PathVariable String key, @Valid @RequestBody MoveRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        return IssueResponse.from(service.move(key, r.status(), r.index(), who.memberId()));
    }

    /** Tomar un issue sin asignar de su etapa (quien tenga uno de los roles responsables). */
    @PostMapping("/api/issues/{key}/take")
    IssueResponse take(@PathVariable String key, @AuthenticationPrincipal AuthenticatedMember who) {
        return IssueResponse.from(service.take(key, who.memberId()));
    }

    /** Entregar el trabajo de la etapa: pasa a la siguiente que permita el flujo. */
    @PostMapping("/api/issues/{key}/deliver")
    IssueResponse deliver(@PathVariable String key, @Valid @RequestBody(required = false) StepRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        return IssueResponse.from(service.deliver(key, r == null ? null : r.comment(), who.memberId()));
    }

    /** Cerrar una puerta: aprobar o rechazar (con motivo). Solo personas con el rol aprobador. */
    /** Quien llama pasa a supervisar el issue: sus agentes, en su cliente de IA, lo atienden. */
    @PostMapping("/api/issues/{key}/supervise")
    IssueResponse supervise(@PathVariable String key, @AuthenticationPrincipal AuthenticatedMember who) {
        return IssueResponse.from(service.supervise(key, who.memberId()));
    }

    @PostMapping("/api/issues/{key}/review")
    IssueResponse review(@PathVariable String key, @Valid @RequestBody ReviewRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        return IssueResponse.from(service.review(key, r.approve(), r.comment(), who.memberId()));
    }

    record StepRequest(@Size(max = 10_000) String comment) {}

    record ReviewRequest(boolean approve, @Size(max = 10_000) String comment) {}

    @DeleteMapping("/api/issues/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String key, @AuthenticationPrincipal AuthenticatedMember who) {
        service.delete(key, who.memberId());
    }

    @GetMapping("/api/issues/{key}/comments")
    List<CommentResponse> comments(@PathVariable String key) {
        return service.comments(key).stream().map(CommentResponse::from).toList();
    }

    @PostMapping("/api/issues/{key}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    CommentResponse comment(
            @PathVariable String key, @Valid @RequestBody CommentRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        return CommentResponse.from(service.comment(key, who.memberId(), r.body()));
    }

    record IssueDetailsRequest(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 50_000) String description,
            @NotNull IssueType type,
            Priority priority,
            @Min(0) @Max(100) Integer storyPoints,
            UUID assigneeId,
            String parentKey,
            List<@Size(max = 40) String> labels) {

        IssueDetails toDomain() {
            return new IssueDetails(title, description, type, priority, storyPoints, assigneeId,
                    parentKey == null || parentKey.isBlank() ? null : parentKey.strip().toUpperCase(), labels);
        }
    }

    record CreateIssueRequest(
            @NotBlank @Size(max = 255) String title,
            @Size(max = 50_000) String description,
            @NotNull IssueType type,
            Priority priority,
            @Min(0) @Max(100) Integer storyPoints,
            UUID assigneeId,
            String parentKey,
            List<@Size(max = 40) String> labels,
            @Size(max = 30) String status) {

        IssueDetails details() {
            return new IssueDetailsRequest(title, description, type, priority, storyPoints, assigneeId, parentKey, labels)
                    .toDomain();
        }
    }

    record AssignRequest(UUID assigneeId) {}

    record MoveRequest(@NotBlank @Size(max = 30) String status, @Min(0) int index) {}

    record CommentRequest(@NotBlank @Size(max = 20_000) String body) {}

    record IssueResponse(
            UUID id, String key, String projectKey, String title, String description, IssueType type,
            String status, Priority priority, Integer storyPoints, UUID assigneeId, UUID reporterId,
            String parentKey, List<String> labels, int position, int reworkCount, UUID supervisorId, String workRole, List<String> dependsOn,
            Instant createdAt, Instant updatedAt) {

        static IssueResponse from(Issue i) {
            return new IssueResponse(i.id(), i.key(), i.projectKey(), i.title(), i.description(), i.type(), i.status(),
                    i.priority(), i.storyPoints(), i.assigneeId(), i.reporterId(), i.parentKey(), i.labels(),
                    i.position(), i.reworkCount(), i.supervisorId(), i.workRole(), i.dependsOn(), i.createdAt(), i.updatedAt());
        }
    }

    record CommentResponse(UUID id, UUID authorId, String body, Instant createdAt) {

        static CommentResponse from(Comment c) {
            return new CommentResponse(c.id(), c.authorId(), c.body(), c.createdAt());
        }
    }
}
