package ec.kuador.board.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.catalog.Methodology;
import ec.kuador.board.catalog.application.CatalogService;
import ec.kuador.board.catalog.domain.CatalogType;
import ec.kuador.board.catalog.IssueType;
import ec.kuador.board.docs.application.DocumentService;
import ec.kuador.board.issue.application.IssueService;
import ec.kuador.board.issue.domain.Issue;
import ec.kuador.board.issue.domain.IssueDetails;
import ec.kuador.board.issue.domain.IssueFilter;
import ec.kuador.board.member.application.MemberService;
import ec.kuador.board.project.application.ProjectService;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.workspace.application.WorkspaceService;
import ec.kuador.board.workspace.domain.Workspace;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Dos espacios con el mismo proyecto KUA en un Postgres real: ninguno ve ni toca lo del otro, ni
 * por listados ni adivinando un id.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceIsolationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired WorkspaceService workspaces;
    @Autowired ProjectService projects;
    @Autowired IssueService issues;
    @Autowired MemberService members;
    @Autowired DocumentService documents;
    @Autowired CatalogService catalog;
    @Autowired AuthService auth;

    private UUID a;
    private UUID b;
    private UUID ownerA;
    private UUID ownerB;

    private static <T> T in(UUID workspace, Supplier<T> work) {
        return TenantContext.call(workspace, work::get);
    }

    @BeforeAll
    void twoWorkspacesWithTheSameProjectKey() {
        a = workspaces.provision(WorkspaceService.Brand.of("acme", "Acme"), new WorkspaceService.Owner("Ana", "ana@acme.test", "clave-de-ana-123")).id();
        b = workspaces.provision(WorkspaceService.Brand.of("beta", "Beta"), new WorkspaceService.Owner("Beto", "beto@beta.test", "clave-de-beto-123")).id();
        ownerA = in(a, () -> members.humans().getFirst().id());
        ownerB = in(b, () -> members.humans().getFirst().id());
        for (UUID ws : new UUID[] {a, b}) {
            in(ws, () -> projects.create("KUA", "Kuador", null, "SOFTWARE"));
            in(ws, () -> projects.setMethodology("KUA", "SDD"));
        }
        in(a, () -> issues.create("KUA", details("Issue de Acme"), ownerA, null));
        in(b, () -> issues.create("KUA", details("Issue de Beta"), ownerB, null));
    }

    private static IssueDetails details(String title) {
        return new IssueDetails(title, null, IssueType.STORY, null, null, null, null, null);
    }

    @Test
    void eachWorkspaceSeesOnlyItsOwnData() {
        assertThat(in(a, () -> issues.get("KUA-1").title())).isEqualTo("Issue de Acme");
        assertThat(in(b, () -> issues.get("KUA-1").title())).isEqualTo("Issue de Beta");
        assertThat(in(a, () -> issues.board("KUA", new IssueFilter(null, null, null)))).extracting(Issue::title).containsExactly("Issue de Acme");
        assertThat(in(a, () -> members.list())).allMatch(m -> !m.id().equals(ownerB));
        assertThat(in(a, () -> members.list())).hasSize(in(b, () -> members.list()).size());
    }

    @Test
    void anIdFromAnotherWorkspaceIsNotFound() {
        UUID docOfB = in(b, () -> documents.create("KUA", "SPEC", "KUA-1", ownerB).document().id());

        assertThatThrownBy(() -> in(a, () -> documents.get(docOfB))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> in(a, () -> documents.save(docOfB, 1, "pisado", null, ownerA))).isInstanceOf(NotFoundException.class);
        assertThat(in(a, () -> members.find(ownerB))).isEmpty();
        assertThat(in(b, () -> documents.get(docOfB).content())).doesNotContain("pisado");
    }

    @Test
    void newWorkspacesComeWithTheGithubMcpServers() {
        assertThat(in(a, () -> catalog.mcpServers())).extracting(s -> s.key()).contains("CONTEXT7", "GITHUB", "GITHUB_LOCAL");
        workspaces.upgradeCatalogs(); // al día: no duplica nada
        assertThat(in(a, () -> catalog.mcpServers())).hasSize(3);
    }

    @Test
    void editingTheCatalogOfOneWorkspaceDoesNotChangeTheOther() {
        Methodology sdd = in(a, () -> catalog.methodology("ODD").orElseThrow());
        in(a, () -> catalog.save(CatalogType.METHODOLOGY, "ODD", new Methodology(null, "ODD", "ODD de Acme", sdd.summary(), sdd.agentRules(),
                sdd.documents(), false, sdd.version())));

        assertThat(in(a, () -> catalog.methodology("ODD").orElseThrow().name())).isEqualTo("ODD de Acme");
        assertThat(in(b, () -> catalog.methodology("ODD").orElseThrow().name())).isNotEqualTo("ODD de Acme");
    }

    @Test
    void loginLandsEachPersonInTheirWorkspace() {
        String token = auth.login("beto@beta.test", "clave-de-beto-123").token();

        assertThat(auth.authenticate(token)).get().satisfies(who -> {
            assertThat(who.workspaceId()).isEqualTo(b);
            assertThat(who.memberId()).isEqualTo(ownerB);
        });
        assertThat(in(a, () -> auth.accounts())).extracting(AuthService.Account::email).containsExactly("ana@acme.test");
        Workspace current = in(b, () -> workspaces.current());
        assertThat(current.slug()).isEqualTo("beta");
    }
}
