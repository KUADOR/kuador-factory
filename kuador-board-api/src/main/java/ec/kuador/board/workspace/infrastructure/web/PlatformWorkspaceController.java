package ec.kuador.board.workspace.infrastructure.web;

import ec.kuador.board.shared.web.AuditHints;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.Workspace;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Quien administra la plataforma crea un espacio sin verificar el correo (demostraciones, cuentas de prueba). */
@RestController
class PlatformWorkspaceController {

    private final SignupService signup;

    PlatformWorkspaceController(SignupService signup) {
        this.signup = signup;
    }

    record CreateRequest(
            @NotBlank @Size(max = 120) String companyName,
            @Size(max = 40) String slug,
            @Size(max = 500) String description,
            @Size(max = 7) String brandColor,
            @NotBlank @Size(max = 120) String ownerName,
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password,
            @Size(max = 30) String plan) {}

    record CreateResponse(java.util.UUID id, String slug, String name) {}

    @PostMapping("/api/platform/workspaces")
    @ResponseStatus(HttpStatus.CREATED)
    CreateResponse create(@Valid @RequestBody CreateRequest r) {
        AuditHints.subject(r.companyName());
        Workspace w = signup.provisionByPlatform(new SignupService.Company(r.companyName(), r.slug(), r.description(), r.brandColor(), null, null),
                r.ownerName(), r.email(), r.password(), r.plan());
        return new CreateResponse(w.id(), w.slug(), w.name());
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(SignupService.AccountExistsException.class)
    org.springframework.http.ProblemDetail exists(SignupService.AccountExistsException e) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Ese correo ya tiene una cuenta.");
    }
}
