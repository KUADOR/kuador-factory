package ec.kuador.board.workspace.infrastructure.web;

import ec.kuador.board.workspace.application.WorkspaceService;
import ec.kuador.board.workspace.domain.Workspace;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspace")
class WorkspaceController {

    private final WorkspaceService service;

    WorkspaceController(WorkspaceService service) {
        this.service = service;
    }

    @GetMapping
    Workspace current() {
        return service.current();
    }

    /** Nombre y marca del espacio (solo personas). `logo` nulo lo quita. */
    @PutMapping
    Workspace update(@Valid @RequestBody UpdateRequest r) {
        return service.updateBranding(r.name(), r.description(), r.brandColor(), r.brandOnColor(), r.logo());
    }

    /** Idioma del equipo (documentos, comentarios, textos para el usuario final) y del código. */
    @PutMapping("/languages")
    Workspace languages(@Valid @RequestBody LanguagesRequest r) {
        return service.updateLanguages(r.team(), r.code());
    }

    record LanguagesRequest(@NotBlank @Size(max = 5) String team, @NotBlank @Size(max = 5) String code) {}

    record UpdateRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @Size(max = 7) String brandColor,
            @Size(max = 7) String brandOnColor,
            @Size(max = Workspace.MAX_LOGO) String logo) {}
}
