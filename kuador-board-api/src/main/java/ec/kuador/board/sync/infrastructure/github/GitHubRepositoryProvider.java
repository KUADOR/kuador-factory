package ec.kuador.board.sync.infrastructure.github;

import ec.kuador.board.shared.crypto.SecretBox;
import ec.kuador.board.shared.github.GitHubClient;

import ec.kuador.board.shared.error.DomainException;
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

/** Repositorios de GitHub: "owner/repo" + rama principal + token (fine-grained) cifrado. */
@Component
class GitHubRepositoryProvider implements RepositoryProvider {

    private static final Pattern REPO = Pattern.compile(
            "^(?:https?://github\\.com/)?([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100}?)(?:\\.git)?/?$");
    private static final Pattern BRANCH = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String apiUrl;
    private final SecretBox secrets;

    GitHubRepositoryProvider(@Value("${board.github.api-url:https://api.github.com}") String apiUrl, SecretBox secrets) {
        this.apiUrl = apiUrl;
        this.secrets = secrets;
    }

    @Override
    public String kind() {
        return "GITHUB";
    }

    @Override
    public Validated validate(LinkSpec spec) {
        Matcher m = REPO.matcher(spec.location() == null ? "" : spec.location().strip());
        if (!m.matches() || m.group(2).equals(".") || m.group(2).equals("..")) {
            throw new DomainException("Indica el repositorio como owner/repo, por ejemplo empresa/proyecto-api.");
        }
        String token = spec.token() == null ? "" : spec.token().strip();
        if (token.isEmpty() || token.length() > 255) {
            throw new DomainException("Pega un token de GitHub (fine-grained) con permisos de Contents y Pull requests sobre el repo.");
        }
        String encrypted = secrets.encrypt(token); // falla temprano si falta BOARD_SECRETS_KEY
        GitHubClient client = new GitHubClient(http, apiUrl, token);
        String requested = m.group(1) + "/" + m.group(2);
        JsonNode repo = client.find("/repos/" + requested)
                .orElseThrow(() -> new DomainException("GitHub no encuentra " + requested + " con ese token (¿nombre correcto? ¿el token tiene acceso?)."));
        JsonNode permissions = repo.path("permissions");
        if (!permissions.isMissingNode() && !permissions.path("push").asBoolean(true)) {
            throw new DomainException("Ese token solo puede leer " + requested + "; necesita escribir para abrir Pull Requests.");
        }
        String branch = spec.branch() == null || spec.branch().isBlank() ? repo.path("default_branch").asString("main") : spec.branch().strip();
        if (!BRANCH.matcher(branch).matches() || branch.contains("..")) {
            throw new DomainException("Nombre de rama no válido: " + branch);
        }
        String fullName = repo.path("full_name").asString(requested);
        if (client.find("/repos/" + fullName + "/git/ref/heads/" + branch).isEmpty()) {
            throw new DomainException("La rama " + branch + " no existe en " + fullName + ".");
        }
        return new Validated(fullName, branch, encrypted);
    }

    @Override
    public RepositoryFiles open(RepositoryLink link) {
        return new GitHubFiles(new GitHubClient(http, apiUrl, secrets.decrypt(link.secret())), link.location(), link.branch());
    }
}
