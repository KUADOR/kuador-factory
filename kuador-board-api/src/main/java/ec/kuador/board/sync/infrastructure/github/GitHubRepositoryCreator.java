package ec.kuador.board.sync.infrastructure.github;

import ec.kuador.board.github.GitHubApi;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.github.GitHubClient;
import ec.kuador.board.sync.domain.RepositoryCreator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Crea el repo en la organización conectada con la app: commit inicial con las bases en {@code main},
 * rama {@code develop} como predeterminada (ahí llegan los PRs de los agentes) y protección de ambas.
 */
@Component
class GitHubRepositoryCreator implements RepositoryCreator {

    static final String MAIN = "main";
    static final String WORK = "develop";
    /** Nombre del job de CI que exigen las protecciones (ver el workflow que genera el tablero). */
    static final String CI_CHECK = "ci";

    private static final Logger log = LoggerFactory.getLogger(GitHubRepositoryCreator.class);
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");

    private final GitHubApi github;
    private final GitHubAppRepositoryProvider provider;

    GitHubRepositoryCreator(GitHubApi github, GitHubAppRepositoryProvider provider) {
        this.github = github;
        this.provider = provider;
    }

    @Override
    public Created create(NewRepository repo, List<File> files, String commitMessage) {
        GitHubApi.Connection connection = github.connection(repo.installationId());
        if (!connection.organization()) {
            throw new DomainException("La app solo crea repos en organizaciones. En la cuenta personal " + connection.account()
                    + ", crea el repo en GitHub y vincúlalo como existente.");
        }
        String name = repo.name() == null ? "" : repo.name().strip();
        if (!NAME.matcher(name).matches() || name.equals(".") || name.equals("..")) {
            throw new DomainException("El nombre del repo va con letras, números, punto, guion o guion bajo (máximo 100).");
        }
        GitHubClient client = provider.client(repo.installationId());
        ObjectNode body = GitHubClient.JSON.createObjectNode().put("name", name).put("private", repo.privateRepo())
                .put("auto_init", true).put("has_wiki", false);
        if (repo.description() != null && !repo.description().isBlank()) body.put("description", repo.description().strip());
        JsonNode created;
        try {
            created = client.post("/orgs/" + connection.account() + "/repos", body);
        } catch (ConflictException e) {
            throw new ConflictException("No se pudo crear " + connection.account() + "/" + name + " (¿ya existe un repo con ese nombre?).");
        }
        String fullName = created.path("full_name").asString();
        String main = created.path("default_branch").asString(MAIN);
        log.info("Repo {} creado con la app", fullName);

        String head = headOf(client, fullName, main);
        String commit = GitData.commit(client, fullName, files.stream().map(f -> new GitData.Entry(f.path(), f.content())).toList(), commitMessage, head);
        GitData.advance(client, fullName, main, commit);
        GitData.createBranch(client, fullName, WORK, commit);
        client.patch("/repos/" + fullName, GitHubClient.JSON.createObjectNode().put("default_branch", WORK));

        List<String> protectedBranches = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        // main: solo por PR, con aprobación de una persona y CI en verde. develop: por PR con CI.
        protect(client, fullName, main, 1, true, protectedBranches, warnings);
        protect(client, fullName, WORK, 0, false, protectedBranches, warnings);
        return new Created(fullName, created.path("html_url").asString(), WORK, protectedBranches, warnings);
    }

    private void protect(GitHubClient client, String fullName, String branch, int approvals, boolean strict, List<String> done, List<String> warnings) {
        ObjectNode rules = GitHubClient.JSON.createObjectNode();
        ObjectNode checks = rules.putObject("required_status_checks").put("strict", strict);
        checks.putArray("contexts").add(CI_CHECK);
        rules.put("enforce_admins", false);
        rules.putObject("required_pull_request_reviews").put("required_approving_review_count", approvals).put("dismiss_stale_reviews", true);
        rules.putNull("restrictions");
        rules.put("allow_force_pushes", false).put("allow_deletions", false);
        try {
            client.put("/repos/" + fullName + "/branches/" + branch + "/protection", rules);
            done.add(branch);
        } catch (GitHubClient.Forbidden e) {
            String message = "GitHub no permitió proteger «" + branch + "»: " + e.githubMessage()
                    + ". En el plan gratuito, las ramas solo se protegen en repos públicos; con GitHub Team, también en privados.";
            if (warnings.stream().noneMatch(w -> w.contains(e.githubMessage()))) warnings.add(message);
            log.warn("Sin protección de {} en {}: {}", branch, fullName, e.githubMessage());
        }
    }

    /** Recién creado con auto_init, el commit inicial puede tardar un instante en verse. */
    private static String headOf(GitHubClient client, String fullName, String branch) {
        for (int i = 0; i < 5; i++) {
            Optional<String> sha = GitData.refSha(client, fullName, branch);
            if (sha.isPresent()) return sha.get();
            try {
                Thread.sleep(800L * (i + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new DomainException("GitHub creó " + fullName + " pero todavía no muestra su rama " + branch + ". Inténtalo de nuevo en un momento.");
    }
}
