package ec.kuador.board.sync.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.sync.domain.RepositoryFiles;
import ec.kuador.board.sync.domain.RepositoryFiles.FileWrite;
import ec.kuador.board.sync.domain.RepositoryFiles.PullRequestState;
import ec.kuador.board.sync.domain.RepositoryLink;
import ec.kuador.board.sync.domain.RepositoryProvider;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubFilesTest {

    private FakeGitHub github;
    private GitHubRepositoryProvider provider;
    private SecretBox secrets;

    @BeforeEach
    void setUp() throws Exception {
        github = new FakeGitHub(Map.of(
                ".specify/memory/constitution.md", "# Constitución\n\nI. Hexagonal",
                "specs/001-lead-capture/spec.md", "# Feature Specification: Captura de leads",
                "specs/001-lead-capture/research.md", "investigación",
                "src/Main.java", "class Main {}"));
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        secrets = new SecretBox(Base64.getEncoder().encodeToString(key));
        provider = new GitHubRepositoryProvider(github.url(), secrets);
    }

    @AfterEach
    void tearDown() {
        github.close();
    }

    private RepositoryFiles open() {
        RepositoryProvider.Validated v = provider.validate(new RepositoryProvider.LinkSpec("https://github.com/kuador/kuador-api.git", "", FakeGitHub.TOKEN));
        return provider.open(new RepositoryLink("KUA", "GITHUB", v.location(), v.branch(), v.secret(), Instant.EPOCH, null));
    }

    @Test
    void validatesRepoBranchAndStoresTheTokenEncrypted() {
        RepositoryProvider.Validated v = provider.validate(new RepositoryProvider.LinkSpec("kuador/kuador-api", null, FakeGitHub.TOKEN));

        assertThat(v.location()).isEqualTo("kuador/kuador-api");
        assertThat(v.branch()).as("rama por defecto del repo").isEqualTo("main");
        assertThat(v.secret()).doesNotContain(FakeGitHub.TOKEN);
        assertThat(secrets.decrypt(v.secret())).isEqualTo(FakeGitHub.TOKEN);
    }

    @Test
    void rejectsBadLinks() {
        assertThatThrownBy(() -> provider.validate(new RepositoryProvider.LinkSpec("kuador/kuador-api", null, "token-malo")))
                .hasMessageContaining("rechazó el token");
        assertThatThrownBy(() -> provider.validate(new RepositoryProvider.LinkSpec("otro/repo", null, FakeGitHub.TOKEN)))
                .hasMessageContaining("no encuentra");
        assertThatThrownBy(() -> provider.validate(new RepositoryProvider.LinkSpec("../../etc", null, FakeGitHub.TOKEN)))
                .hasMessageContaining("owner/repo");
        assertThatThrownBy(() -> provider.validate(new RepositoryProvider.LinkSpec("kuador/kuador-api", "no-existe", FakeGitHub.TOKEN)))
                .hasMessageContaining("no existe");
        github.canPush = false;
        assertThatThrownBy(() -> provider.validate(new RepositoryProvider.LinkSpec("kuador/kuador-api", null, FakeGitHub.TOKEN)))
                .hasMessageContaining("solo puede leer");
        assertThatThrownBy(() -> new GitHubRepositoryProvider(github.url(), new SecretBox(""))
                .validate(new RepositoryProvider.LinkSpec("kuador/kuador-api", null, FakeGitHub.TOKEN)))
                .hasMessageContaining("BOARD_SECRETS_KEY");
    }

    @Test
    void readsMainWithOneTreeRequest() {
        RepositoryFiles files = open();

        assertThat(files.read("specs/001-lead-capture/spec.md")).contains("# Feature Specification: Captura de leads");
        assertThat(files.read("PRODUCT.md")).isEmpty();
        assertThat(files.listMarkdown("specs", 2)).containsExactly("specs/001-lead-capture/research.md", "specs/001-lead-capture/spec.md");
        assertThat(files.listMarkdown("", 1)).isEmpty();
        files.read("specs/001-lead-capture/spec.md");
        assertThat(github.requests.stream().filter(r -> r.contains("/git/trees/")).count()).as("árbol cacheado").isEqualTo(1);
        assertThatThrownBy(() -> files.read("../secret.md")).isInstanceOf(DomainException.class);
    }

    @Test
    void publishOpensAPullRequestWithAllFilesInOneCommitAndLeavesMainUntouched() {
        RepositoryFiles files = open();

        RepositoryFiles.Published p = files.publish(List.of(
                        new FileWrite(".specify/memory/constitution.md", "# Constitución\n\nI. Hexagonal\nII. OpenAPI"),
                        new FileWrite("specs/008-prueba/spec.md", "# Spec nueva")),
                "docs: actualiza", "Documentos desde el tablero", "cuerpo", null);

        assertThat(p.direct()).isFalse();
        assertThat(p.pullRequest()).isEqualTo(1);
        assertThat(p.url()).isEqualTo("https://github.com/kuador/kuador-api/pull/1");
        assertThat(p.branch()).startsWith("kuador/docs-");
        assertThat(github.file("main", ".specify/memory/constitution.md")).as("main intacto hasta fusionar").doesNotContain("OpenAPI");
        assertThat(github.file(p.branch(), ".specify/memory/constitution.md")).contains("II. OpenAPI");
        assertThat(github.file(p.branch(), "specs/008-prueba/spec.md")).isEqualTo("# Spec nueva");
        assertThat(github.file(p.branch(), "src/Main.java")).as("el resto del repo se conserva").isEqualTo("class Main {}");
        assertThat(github.requests.stream().filter(r -> r.equals("POST /repos/kuador/kuador-api/git/commits")).count()).isEqualTo(1);
        assertThat(github.pulls.get(1)).containsEntry("base", "main").containsEntry("head", p.branch());
        assertThat(files.pullRequestState(1)).contains(PullRequestState.OPEN);

        github.merge(1);
        assertThat(files.pullRequestState(1)).contains(PullRequestState.MERGED);
        assertThat(github.file("main", ".specify/memory/constitution.md")).contains("II. OpenAPI");
    }

    @Test
    void publishToAnExistingBranchAddsACommitToTheSamePullRequest() {
        RepositoryFiles files = open();
        RepositoryFiles.Published first = files.publish(List.of(new FileWrite("PRODUCT.md", "# v1")), "m1", "t", "d", null);

        RepositoryFiles.Published second = files.publish(List.of(new FileWrite("AGENTS.md", "# reglas")), "m2", "t", "d", first.branch());

        assertThat(second.pullRequest()).as("no abre otro PR").isNull();
        assertThat(github.pulls).hasSize(1);
        assertThat(github.file(first.branch(), "PRODUCT.md")).isEqualTo("# v1");
        assertThat(github.file(first.branch(), "AGENTS.md")).isEqualTo("# reglas");

        github.refs.remove(first.branch()); // alguien borró la rama
        RepositoryFiles.Published third = files.publish(List.of(new FileWrite("AGENTS.md", "# reglas v2")), "m3", "t", "d", first.branch());
        assertThat(third.pullRequest()).as("sin rama, abre un PR nuevo").isEqualTo(2);
    }

    @Test
    void tokenIsSentOnlyAsBearerHeader() {
        open().read("PRODUCT.md");
        assertThat(github.requests).allMatch(r -> !r.contains(FakeGitHub.TOKEN));
    }
}
