package ec.kuador.board.project.infrastructure.web;

import ec.kuador.board.project.SubjectView;
import ec.kuador.board.project.application.SubjectService;
import ec.kuador.board.project.application.SubjectService.SubjectWithBoards;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Los proyectos del espacio y los que atiende cada tablero. Quién puede cambiar qué lo fija la seguridad. */
@RestController
class SubjectController {

    private final SubjectService service;
    private final ec.kuador.board.project.application.ProjectService boards;

    SubjectController(SubjectService service, ec.kuador.board.project.application.ProjectService boards) {
        this.service = service;
        this.boards = boards;
    }

    @GetMapping("/api/subjects")
    List<SubjectResponse> list() {
        return service.list().stream().map(SubjectResponse::from).toList();
    }

    /** {@code key} vacía = la pone el tablero a partir del nombre. */
    @PostMapping("/api/subjects")
    @ResponseStatus(HttpStatus.CREATED)
    SubjectResponse create(@Valid @RequestBody SubjectRequest r) {
        return SubjectResponse.from(service.create(r.key(), r.name(), r.description()));
    }

    @GetMapping("/api/subjects/{key}")
    SubjectResponse get(@PathVariable String key) {
        return SubjectResponse.from(service.get(key));
    }

    @PutMapping("/api/subjects/{key}")
    SubjectResponse update(@PathVariable String key, @Valid @RequestBody SubjectRequest r) {
        return SubjectResponse.from(service.update(key, r.name(), r.description()));
    }

    /** El manual de trabajo del proyecto ({@code methodology} nulo = sin manual): vale en todos sus tableros. */
    @PutMapping("/api/subjects/{key}/methodology")
    SubjectResponse setMethodology(@PathVariable String key, @RequestBody MethodologyRequest r) {
        boards.setSubjectMethodology(key, r.methodology() == null || r.methodology().isBlank() ? null : r.methodology());
        return SubjectResponse.from(service.get(key));
    }

    record MethodologyRequest(@Size(max = 40) String methodology) {}

    @DeleteMapping("/api/subjects/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String key) {
        service.delete(key);
    }

    @GetMapping("/api/projects/{projectKey}/subjects")
    List<SubjectView> ofBoard(@PathVariable String projectKey) {
        return service.ofBoard(projectKey);
    }

    /** Suma un proyecto al tablero: uno que ya existe ({@code subjectKey}) o uno nuevo ({@code name}). */
    @PostMapping("/api/projects/{projectKey}/subjects")
    List<SubjectView> add(@PathVariable String projectKey, @Valid @RequestBody AddRequest r) {
        if (r.subjectKey() != null && !r.subjectKey().isBlank()) return service.addToBoard(projectKey, r.subjectKey());
        return service.createInBoard(projectKey, r.key(), r.name(), r.description());
    }

    @DeleteMapping("/api/projects/{projectKey}/subjects/{subjectKey}")
    List<SubjectView> remove(@PathVariable String projectKey, @PathVariable String subjectKey) {
        return service.removeFromBoard(projectKey, subjectKey);
    }

    record SubjectRequest(@Size(max = 10) String key, @NotBlank @Size(max = 120) String name, @Size(max = 2000) String description) {}

    record AddRequest(@Size(max = 10) String subjectKey, @Size(max = 10) String key, @Size(max = 120) String name, @Size(max = 2000) String description) {}

    record SubjectResponse(String key, String name, String description, Instant createdAt, List<String> boards, String methodology) {
        static SubjectResponse from(SubjectWithBoards s) {
            return new SubjectResponse(s.subject().key(), s.subject().name(), s.subject().description(), s.subject().createdAt(), s.boards(),
                    s.methodology());
        }
    }
}
