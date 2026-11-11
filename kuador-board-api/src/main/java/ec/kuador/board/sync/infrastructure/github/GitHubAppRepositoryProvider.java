package ec.kuador.board.sync.infrastructure.github;

import ec.kuador.board.github.GitHubApi;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.github.GitHubClient;
import ec.kuador.board.sync.domain.RepositoryFiles;
import ec.kuador.board.sync.domain.RepositoryLink;
import ec.kuador.board.sync.domain.RepositoryProvider;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Repositorios de GitHub con la app de la plataforma: sin token personal. Cada uso pide un token de
 * instalación de corta duración a la conexión de GitHub del espacio.
 */
@Component
class GitHubAppRepositoryProvider implements RepositoryProvider {

    static final String KIND = "GITHUB_APP";
    private static final Pattern REPO = Pattern.compile(
            "^(?:https?://github\\.com/)?([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100}?)(?:\\.git)?/?$");
    private static final Pattern BRANCH = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String apiUrl;
    private final GitHubApi github;

    GitHubAppRepositoryProvider(@Value("${board.github.api-url:https://api.github.com}") String apiUrl, GitHubApi github) {
        this.apiUrl = apiUrl;
        this.github = github;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Validated validate(LinkSpec spec) {
        if (spec.installationId() == null) throw new DomainException("Elige la conexión de GitHub del espacio.");
        GitHubApi.Connection connection = github.connection(spec.installationId());
        Matcher m = REPO.matcher(spec.location() == null ? "" : spec.location().strip());
        if (!m.matches() || m.group(2).equals(".") || m.group(2).equals("..")) {
            throw new DomainException("Indica el repositorio como owner/repo, por ejemplo " + connection.account() + "/proyecto-api.");
        }
        String requested = m.group(1) + "/" + m.group(2);
        GitHubClient client = client(spec.installationId());
        JsonNode repo = client.find("/repos/" + requested).orElseThrow(() -> new DomainException(
                "La app no tiene acceso a " + requested + ". En GitHub, agrégalo a los repositorios de la instalación en " + connection.account() + "."));
        String branch = spec.branch() == null || spec.branch().isBlank() ? repo.path("default_branch").asString("main") : spec.branch().strip();
        if (!BRANCH.matcher(branch).matches() || branch.contains("..")) throw new DomainException("Nombre de rama no válido: " + branch);
        String fullName = repo.path("full_name").asString(requested);
        if (GitData.refSha(client, fullName, branch).isEmpty()) throw new DomainException("La rama " + branch + " no existe en " + fullName + ".");
        return new Validated(fullName, branch, String.valueOf(spec.installationId()));
    }

    @Override
    public RepositoryFiles open(RepositoryLink link) {
        return new GitHubFiles(client(Long.parseLong(link.secret())), link.location(), link.branch());
    }

    GitHubClient client(long installationId) {
        return new GitHubClient(http, apiUrl, github.installationToken(installationId));
    }
}
