package ec.kuador.board.github.infrastructure.web;

import ec.kuador.board.auth.AuthenticatedMember;
import ec.kuador.board.github.application.GitHubAppService;
import ec.kuador.board.github.domain.Installation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Conexiones de GitHub del espacio y registro de la GitHub App. Solo personas que editan. */
@RestController
class GitHubController {

    private final GitHubAppService service;

    GitHubController(GitHubAppService service) {
        this.service = service;
    }

    record AppResponse(String slug, String name, String ownerLogin, String htmlUrl) {}

    record ConnectionResponse(long installationId, String account, String accountType, Instant linkedAt) {
        static ConnectionResponse from(Installation i) {
            return new ConnectionResponse(i.installationId(), i.accountLogin(), i.accountType(), i.linkedAt());
        }
    }

    record OverviewResponse(AppResponse app, boolean canRegister, List<ConnectionResponse> connections) {}

    @GetMapping("/api/github")
    OverviewResponse overview(@AuthenticationPrincipal AuthenticatedMember who) {
        GitHubAppService.Overview o = service.overview(who.memberId());
        return new OverviewResponse(o.app().map(a -> new AppResponse(a.slug(), a.name(), a.ownerLogin(), a.htmlUrl())).orElse(null),
                o.canRegister(), o.installations().stream().map(ConnectionResponse::from).toList());
    }

    record ManifestRequest(@NotBlank String appName, String organization) {}

    @PostMapping("/api/github/app/manifest")
    GitHubAppService.ManifestForm manifest(@Valid @RequestBody ManifestRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        return service.manifest(r.appName(), r.organization(), who.memberId());
    }

    record CompleteAppRequest(@NotBlank String code, @NotBlank String state) {}

    @PostMapping("/api/github/app")
    @ResponseStatus(HttpStatus.CREATED)
    AppResponse completeRegistration(@Valid @RequestBody CompleteAppRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        GitHubAppService.AppView a = service.completeRegistration(r.code(), r.state(), who.memberId());
        return new AppResponse(a.slug(), a.name(), a.ownerLogin(), a.htmlUrl());
    }

    record InstallUrlResponse(String url) {}

    @PostMapping("/api/github/installations/start")
    InstallUrlResponse startInstallation(@AuthenticationPrincipal AuthenticatedMember who) {
        return new InstallUrlResponse(service.installUrl(who.memberId()));
    }

    record CompleteInstallationRequest(@NotNull Long installationId, @NotBlank String code, @NotBlank String state) {}

    @PostMapping("/api/github/installations")
    @ResponseStatus(HttpStatus.CREATED)
    ConnectionResponse completeInstallation(@Valid @RequestBody CompleteInstallationRequest r, @AuthenticationPrincipal AuthenticatedMember who) {
        return ConnectionResponse.from(service.completeInstallation(r.installationId(), r.code(), r.state(), who.memberId()));
    }

    @DeleteMapping("/api/github/installations/{installationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disconnect(@PathVariable long installationId) {
        service.disconnect(installationId);
    }
}
