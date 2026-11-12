package ec.kuador.board.project.infrastructure.web;

import ec.kuador.board.catalog.ColumnCategory;
import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.catalog.IssueTypeDef;
import ec.kuador.board.catalog.ProjectKind;
import ec.kuador.board.project.Transitions;
import ec.kuador.board.project.application.ProjectService;
import ec.kuador.board.project.Transitions;
import ec.kuador.board.project.application.ProjectService.ProjectWithColumns;
import ec.kuador.board.project.domain.Workflow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
class ProjectController {

    private final ProjectService service;

    ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    List<ProjectResponse> list() {
        return service.list().stream().map(ProjectResponse::from).toList();
    }

    @GetMapping("/{key}")
    ProjectResponse get(@PathVariable String key) {
        return ProjectResponse.from(service.get(key));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProjectResponse create(@Valid @RequestBody CreateProjectRequest r) {
        return ProjectResponse.from(service.create(r.key(), r.name(), r.description(), r.templateKey()));
    }

    /**
     * Borra el tablero con todo su contenido. {@code confirm} debe ser la clave del tablero. Solo personas que
     * editan (lo fija la seguridad); los repositorios en GitHub no se tocan.
     */
    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String key, @org.springframework.web.bind.annotation.RequestParam(required = false) String confirm,
            @org.springframework.security.core.annotation.AuthenticationPrincipal(expression = "memberId()") java.util.UUID actorId) {
        service.delete(key, confirm, actorId);
    }

    /** Reemplaza el flujo completo, en el orden recibido. {@code key} nulo = columna nueva. */
    @PutMapping("/{key}/columns")
    ProjectResponse updateColumns(@PathVariable String key, @Valid @RequestBody UpdateColumnsRequest r) {
        return ProjectResponse.from(service.updateColumns(key,
                r.columns().stream().map(c -> new Workflow.Draft(c.key(), c.name(), c.category())).toList()));
    }

    /** {@code methodology} nulo = sin metodología. */
    @PutMapping("/{key}/methodology")
    ProjectResponse setMethodology(@PathVariable String key, @RequestBody MethodologyRequest r) {
        return ProjectResponse.from(service.setMethodology(key, r.methodology()));
    }

    /** Nombre y descripción. */
    @PutMapping("/{key}")
    ProjectResponse updateDetails(@PathVariable String key, @Valid @RequestBody DetailsRequest r) {
        return ProjectResponse.from(service.updateDetails(key, r.name(), r.description()));
    }

    /** Reglas de transición: {@code restricted} falso = movimiento libre. */
    @PutMapping("/{key}/transitions")
    ProjectResponse setTransitions(@PathVariable String key, @Valid @RequestBody TransitionsRequest r) {
        return ProjectResponse.from(service.setTransitions(key, new Transitions(r.restricted(),
                r.allowed() == null ? List.of() : r.allowed().stream().map(t -> new Transitions.Transition(t.from(), t.to())).toList())));
    }

    /** Etapas de las columnas y freno de bucles. */
    @PutMapping("/{key}/stages")
    ProjectResponse setStages(@PathVariable String key, @Valid @RequestBody StagesRequest r) {
        return ProjectResponse.from(service.setStages(key, r.stages() == null ? List.of() : r.stages(), r.maxRework()));
    }

    record StagesRequest(@Size(max = 12) List<StageDef> stages, int maxRework) {}

    record DetailsRequest(@NotBlank @Size(max = 120) String name, @Size(max = 2000) String description) {}

    record TransitionRequest(@NotBlank String from, @NotBlank String to) {}

    record TransitionsRequest(boolean restricted, @Size(max = 200) List<@Valid TransitionRequest> allowed) {}

    record CreateProjectRequest(
            @NotBlank @Size(max = 10) String key,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 4000) String description,
            @NotBlank @Size(max = 40) String templateKey) {}

    record ColumnRequest(String key, String name, ColumnCategory category) {}

    record UpdateColumnsRequest(@NotEmpty List<ColumnRequest> columns) {}

    record MethodologyRequest(String methodology) {}

    record ProjectResponse(
            UUID id, String key, String name, String description, ProjectKind kind, String templateKey, String methodology,
            List<ColumnDef> columns, List<IssueTypeDef> issueTypes, Transitions transitions, List<StageDef> stages, int maxRework,
            Instant createdAt) {

        static ProjectResponse from(ProjectWithColumns pc) {
            var p = pc.project();
            return new ProjectResponse(p.id(), p.key(), p.name(), p.description(), p.kind(), p.templateKey(), p.methodology(),
                    pc.columns(), p.issueTypes(), p.transitions(), p.stages(), p.maxRework(), p.createdAt());
        }
    }
}
