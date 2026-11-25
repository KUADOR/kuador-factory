package ec.kuador.board.workspace.infrastructure.web;

import ec.kuador.board.shared.web.AuditHints;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.Workspace;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Alta pública de espacios de trabajo (sin sesión). */
@RestController
@RequestMapping("/api/signup")
class SignupController {

    private final SignupService signup;

    private static final org.springframework.web.server.ResponseStatusException TOO_MANY = new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos desde esta conexión. Espera unos minutos e inténtalo de nuevo.");
    private final ec.kuador.board.shared.web.RateLimiter perIp;
    private final ec.kuador.board.shared.web.RateLimiter verifyPerIp;
    private final ec.kuador.board.shared.web.WebSecret web;

    SignupController(SignupService signup, ec.kuador.board.shared.web.WebSecret web, ec.kuador.board.shared.web.RateLimits limits) {
        this.perIp = limits.limiter("alta-codigo-ip", 10, java.time.Duration.ofMinutes(10));
        this.verifyPerIp = limits.limiter("alta-verificar-ip", 20, java.time.Duration.ofMinutes(10));
        this.web = web;
        this.signup = signup;
    }

    record CodeRequest(@NotBlank @Size(max = 254) String email) {}

    record VerifyRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 12) String code) {}

    record VerifyResponse(String signupToken) {}

    record CompleteRequest(
            @NotBlank String signupToken,
            @NotBlank @Size(max = 120) String companyName,
            @Size(max = 40) String slug,
            @Size(max = 500) String description,
            @Size(max = 7) String brandColor,
            @Size(max = 7) String brandOnColor,
            @Size(max = Workspace.MAX_LOGO) String logo,
            @NotBlank @Size(max = 120) String ownerName,
            @NotBlank @Size(max = 128) String password,
            @Size(max = 30) String plan,
            @Size(max = 30) String packageCode) {}

    record CompleteResponse(String slug, String name) {}

    @PostMapping("/code")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void code(@Valid @RequestBody CodeRequest r, jakarta.servlet.http.HttpServletRequest http) {
        if (perIp.hit(web.clientIp(http), java.time.Instant.now())) throw TOO_MANY;
        AuditHints.subject(r.email());
        signup.requestCode(r.email());
    }

    @PostMapping("/verify")
    VerifyResponse verify(@Valid @RequestBody VerifyRequest r, jakarta.servlet.http.HttpServletRequest http) {
        if (verifyPerIp.hit(web.clientIp(http), java.time.Instant.now())) throw TOO_MANY;
        AuditHints.subject(r.email());
        return new VerifyResponse(signup.verifyCode(r.email(), r.code()));
    }

    @PostMapping("/workspace")
    @ResponseStatus(HttpStatus.CREATED)
    CompleteResponse complete(@Valid @RequestBody CompleteRequest r) {
        Workspace w = signup.complete(r.signupToken(),
                new SignupService.Company(r.companyName(), r.slug(), r.description(), r.brandColor(), r.brandOnColor(), r.logo()), r.ownerName(), r.password(), r.plan(), r.packageCode());
        AuditHints.subject(w.name());
        return new CompleteResponse(w.slug(), w.name());
    }

    @GetMapping("/slug")
    CompleteResponse slug(@RequestParam @Size(max = 120) String name) {
        return new CompleteResponse(signup.suggestSlug(name), name);
    }

    @ExceptionHandler(SignupService.AccountExistsException.class)
    ProblemDetail exists(SignupService.AccountExistsException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Cuenta existente");
        pd.setProperty("code", "ACCOUNT_EXISTS");
        return pd;
    }

    @ExceptionHandler(SignupService.TooManyRequestsException.class)
    ProblemDetail tooMany(SignupService.TooManyRequestsException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        pd.setTitle("Demasiadas solicitudes");
        return pd;
    }
}
