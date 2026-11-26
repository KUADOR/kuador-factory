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
    private final ec.kuador.board.project.application.SubjectService subjects;

    ProjectController(ProjectService service, ec.kuador.board.project.application.SubjectService subjects) {
        this.service = service;
        this.subjects = subjects;
    }

    /** El tablero con los proyectos que atiende (para elegir uno al crear una tarjeta, filtrar y rotular). */
    private ProjectResponse response(ProjectWithColumns pc) {
        return ProjectResponse.from(pc, subjects.ofBoard(pc.project().key()));
    }

    @GetMapping
    List<ProjectResponse> list() {
        return service.list().stream().map(this::response).toList();
    }

    @GetMapping("/{key}")
    ProjectResponse get(@PathVariable String key) {
        return response(service.get(key));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProjectResponse create(@org.springframework.security.core.annotation.AuthenticationPrincipal(expression = "memberId()") UUID actorId,
            @Valid @RequestBody CreateProjectRequest r) {
        return response(service.create(r.key(), r.name(), r.description(), r.templateKey(), actorId, r.subjects()));
    }

    /** El objetivo del tablero: lo leen los agentes con cada tarjeta. */
    @GetMapping("/{key}/goal")
    java.util.Map<String, String> goal(@PathVariable String key) {
        return java.util.Collections.singletonMap("goal", service.goal(key.toUpperCase(java.util.Locale.ROOT)).orElse(null));
    }

    @org.springframework.web.bind.annotation.PutMapping("/{key}/goal")
    java.util.Map<String, String> setGoal(@PathVariable String key, @RequestBody GoalRequest r) {
        return java.util.Collections.singletonMap("goal", service.setGoal(key.toUpperCase(java.util.Locale.ROOT), r.goal()).orElse(null));
    }

    record GoalRequest(@jakarta.validation.constraints.Size(max = 1000) String goal) {}

    /** Las personas del espacio y si están en el tablero (quien administra lo ve por su nivel). */
    @GetMapping("/{key}/people")
    List<ProjectService.BoardPerson> people(@PathVariable String key) {
        return service.people(key.toUpperCase());
    }

    record AddPersonRequest(@jakarta.validation.constraints.NotNull UUID memberId) {}

    /** Suma una persona al tablero (solo quien administra, lo fija la seguridad). */
    @PostMapping("/{key}/people")
    List<ProjectService.BoardPerson> addPerson(@PathVariable String key, @Valid @RequestBody AddPersonRequest r) {
        return service.addToBoard(key.toUpperCase(), r.memberId());
    }

    @DeleteMapping("/{key}/people/{memberId}")
    List<ProjectService.BoardPerson> removePerson(@PathVariable String key, @PathVariable UUID memberId) {
        return service.removePerson(key.toUpperCase(), memberId);
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
        return response(service.updateColumns(key,
                r.columns().stream().map(c -> new Workflow.Draft(c.key(), c.name(), c.category())).toList()));
    }

    /** {@code methodology} nulo = sin metodología. */
    @PutMapping("/{key}/methodology")
    ProjectResponse setMethodology(@PathVariable String key, @RequestBody MethodologyRequest r) {
        return response(service.setMethodology(key, r.methodology()));
    }

    /** Nombre y descripción. */
    @PutMapping("/{key}")
    ProjectResponse updateDetails(@PathVariable String key, @Valid @RequestBody DetailsRequest r) {
        return response(service.updateDetails(key, r.name(), r.description()));
    }

    /** Reglas de transición: {@code restricted} falso = movimiento libre. */
    @PutMapping("/{key}/transitions")
    ProjectResponse setTransitions(@PathVariable String key, @Valid @RequestBody TransitionsRequest r) {
        return response(service.setTransitions(key, new Transitions(r.restricted(),
                r.allowed() == null ? List.of() : r.allowed().stream().map(t -> new Transitions.Transition(t.from(), t.to())).toList())));
    }

    /** Etapas de las columnas y freno de bucles. */
    @PutMapping("/{key}/stages")
    ProjectResponse setStages(@PathVariable String key, @Valid @RequestBody StagesRequest r) {
        return response(service.setStages(key, r.stages() == null ? List.of() : r.stages(), r.maxRework()));
    }

    /** Qué usa el tablero: documentos y reparto en tareas. */
    @PutMapping("/{key}/capabilities")
    ProjectResponse setCapabilities(@PathVariable String key, @RequestBody CapabilitiesRequest r) {
        return response(service.setCapabilities(key, r.capabilities()));
    }

    record CapabilitiesRequest(@Size(max = 8) List<ec.kuador.board.catalog.Capability> capabilities) {}

    /** La forma de la tarjeta: nombre de cada tipo, sus datos propios y en qué se mide. */
    @PutMapping("/{key}/card")
    ProjectResponse setCard(@PathVariable String key, @RequestBody CardRequest r) {
        return response(service.setCard(key, r.issueTypes(), r.estimate()));
    }

    record CardRequest(@Size(max = 4) List<IssueTypeDef> issueTypes, ec.kuador.board.catalog.EstimateUnit estimate) {}

    record StagesRequest(@Size(max = 12) List<StageDef> stages, int maxRework) {}

    record DetailsRequest(@NotBlank @Size(max = 120) String name, @Size(max = 2000) String description) {}

    record TransitionRequest(@NotBlank String from, @NotBlank String to) {}

    record TransitionsRequest(boolean restricted, @Size(max = 200) List<@Valid TransitionRequest> allowed) {}

    record CreateProjectRequest(
            @NotBlank @Size(max = 10) String key,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 4000) String description,
            @NotBlank @Size(max = 40) String templateKey,
            @Size(max = 30) List<@Size(max = 10) String> subjects) {}

    record ColumnRequest(String key, String name, ColumnCategory category) {}

    record UpdateColumnsRequest(@NotEmpty List<ColumnRequest> columns) {}

    record MethodologyRequest(String methodology) {}

    record ProjectResponse(
            UUID id, String key, String name, String description, ProjectKind kind, String templateKey, String methodology,
            List<ColumnDef> columns, List<IssueTypeDef> issueTypes, Transitions transitions, List<StageDef> stages, int maxRework,
            Instant createdAt, List<ec.kuador.board.catalog.Capability> capabilities, ec.kuador.board.catalog.EstimateUnit estimate,
            List<ec.kuador.board.project.SubjectView> subjects) {

        static ProjectResponse from(ProjectWithColumns pc, List<ec.kuador.board.project.SubjectView> subjects) {
            var p = pc.project();
            return new ProjectResponse(p.id(), p.key(), p.name(), p.description(), p.kind(), p.templateKey(), p.methodology(),
                    pc.columns(), p.issueTypes(), p.transitions(), p.stages(), p.maxRework(), p.createdAt(), p.capabilities(), p.estimate(), subjects);
        }
    }
}
