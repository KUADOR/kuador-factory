package ec.kuador.board.github;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.member.application.MemberService;
import ec.kuador.board.member.domain.Member;
import ec.kuador.board.member.domain.MemberKind;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import ec.kuador.board.workspace.domain.Workspace;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GitHub App de punta a punta contra un GitHub falso: registro con el manifiesto, instalación con
 * verificación OAuth, repo nuevo con sus bases (ramas, CI, AGENTS.md, metodología) y protección de ramas.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=", "board.agents.anthropic-api-key=",
        "board.sync.allowed-roots=", "spring.config.import=optional:file:does-not-exist.env[.properties]",
        "board.platform.workspace=acme-lab", "board.web-url=http://web.test"})
class GitHubAppIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static final FakeGitHubCloud GITHUB;

    static {
        try {
            GITHUB = new FakeGitHubCloud();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void github(DynamicPropertyRegistry r) {
        r.add("board.github.api-url", GITHUB::url);
        r.add("board.github.web-url", GITHUB::url);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        r.add("board.secrets-key", () -> Base64.getEncoder().encodeToString(key));
    }

    @AfterAll
    static void stop() {
        GITHUB.close();
    }

    static final Map<String, String> INBOX = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Mail {
        @Bean
        @Primary
        VerificationMailer inbox() {
            return INBOX::put;
        }
    }

    @Autowired SignupService signup;
    @Autowired AuthService auth;
    @Autowired MemberService members;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    record Reply(int status, JsonNode body) {}

    private Reply call(String method, String path, Object body, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) req.header("Authorization", "Bearer " + token);
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(), res.body().isBlank() ? null : json.readTree(res.body()));
    }

    private static String state(String url) {
        String q = URI.create(url).getRawQuery();
        return URLDecoder.decode(q.substring(q.indexOf("state=") + 6), StandardCharsets.UTF_8);
    }

    @Test
    void registerInstallAndCreateARepositoryWithItsBases() throws Exception {
        signup.requestCode("ana@acme.test");
        Workspace w = signup.complete(signup.verifyCode("ana@acme.test", INBOX.get("ana@acme.test")),
                new SignupService.Company("Acme Lab", null, null, null, null, null), "Ana", "clave-de-ana-123");
        assertThat(w.slug()).isEqualTo("acme-lab");
        String owner = auth.login("ana@acme.test", "clave-de-ana-123").token();
        Member dev = TenantContext.call(w.id(), () -> members.create("Dev", MemberKind.HUMAN, "DEV", null, "#cccccc", null));
        TenantContext.run(w.id(), () -> auth.grantAccess(dev.id(), "dev@acme.test", "clave-del-dev-1"));
        String devSession = auth.login("dev@acme.test", "clave-del-dev-1").token();

        // Registro de la app: solo la dueña del espacio que opera la plataforma.
        assertThat(call("GET", "/api/github", null, owner).body().get("canRegister").asBoolean()).isTrue();
        assertThat(call("GET", "/api/github", null, devSession).body().get("canRegister").asBoolean()).isFalse();
        assertThat(call("POST", "/api/github/app/manifest", Map.of("appName", "Tablero Acme", "organization", "acme"), devSession).status()).isEqualTo(403);
        JsonNode form = call("POST", "/api/github/app/manifest", Map.of("appName", "Tablero Acme", "organization", "acme"), owner).body();
        assertThat(form.get("postUrl").asString()).startsWith(GITHUB.url() + "/organizations/acme/settings/apps/new?state=");
        JsonNode manifest = json.readTree(form.get("manifest").asString());
        assertThat(manifest.get("redirect_url").asString()).isEqualTo("http://web.test/conexiones/github/app-creada");
        assertThat(manifest.get("request_oauth_on_install").asBoolean()).isTrue();
        assertThat(manifest.get("hook_attributes").get("active").asBoolean()).isFalse();
        assertThat(manifest.get("default_permissions").get("administration").asString()).isEqualTo("write");
        String registerState = state(form.get("postUrl").asString());

        assertThat(call("POST", "/api/github/app", Map.of("code", FakeGitHubCloud.MANIFEST_CODE, "state", "otro"), owner).status()).isEqualTo(403);
        Reply registered = call("POST", "/api/github/app", Map.of("code", FakeGitHubCloud.MANIFEST_CODE, "state", registerState), owner);
        assertThat(registered.status()).isEqualTo(201);
        assertThat(registered.body().get("slug").asString()).isEqualTo("tablero-acme");
        assertThat(call("POST", "/api/github/app", Map.of("code", FakeGitHubCloud.MANIFEST_CODE, "state", registerState), owner).status())
                .as("el estado sirve una sola vez").isEqualTo(403);
        String storedKey = jdbc.queryForObject("select private_key from github_app", String.class);
        assertThat(storedKey).as("la clave queda cifrada").doesNotContain("PRIVATE KEY");

        // Instalación: se comprueba con OAuth que quien vuelve administra esa instalación.
        String installUrl = call("POST", "/api/github/installations/start", null, owner).body().get("url").asString();
        assertThat(installUrl).startsWith(GITHUB.url() + "/apps/tablero-acme/installations/new?state=");
        Reply foreign = call("POST", "/api/github/installations", Map.of("installationId", 99, "code", FakeGitHubCloud.OAUTH_CODE,
                "state", state(installUrl)), owner);
        assertThat(foreign.status()).isEqualTo(403);
        assertThat(foreign.body().get("detail").asString()).contains("no administra");
        installUrl = call("POST", "/api/github/installations/start", null, owner).body().get("url").asString();
        Reply installed = call("POST", "/api/github/installations", Map.of("installationId", FakeGitHubCloud.INSTALLATION,
                "code", FakeGitHubCloud.OAUTH_CODE, "state", state(installUrl)), owner);
        assertThat(installed.status()).isEqualTo(201);
        assertThat(installed.body().get("account").asString()).isEqualTo("acme");
        assertThat(call("GET", "/api/github", null, devSession).body().get("connections").get(0).get("account").asString()).isEqualTo("acme");

        // Repo nuevo, privado: bases arriba, develop predeterminada; GitHub no deja proteger (plan gratuito).
        call("POST", "/api/projects", Map.of("key", "PI", "name", "Portal Pi", "templateKey", "SOFTWARE_FACTORY"), owner);
        call("PUT", "/api/projects/PI/methodology", Map.of("methodology", "SDD"), owner);
        Reply repo = call("POST", "/api/projects/PI/repository/new", Map.of("installationId", FakeGitHubCloud.INSTALLATION, "name", "pi-api",
                "description", "Portal de pacientes", "privateRepo", true), owner);
        assertThat(repo.status()).as(String.valueOf(repo.body())).isEqualTo(201);
        assertThat(repo.body().get("link").get("kind").asString()).isEqualTo("GITHUB_APP");
        assertThat(repo.body().get("link").get("branch").asString()).isEqualTo("develop");
        assertThat(repo.body().get("protectedBranches")).isEmpty();
        assertThat(repo.body().get("warnings").get(0).asString()).contains("plan gratuito", "Upgrade to GitHub Pro");
        assertThat(repo.body().get("documents").asInt()).isPositive();
        assertThat(GITHUB.repos.get("acme/pi-api").get("default_branch")).isEqualTo("develop");
        assertThat(GITHUB.refs.get("acme/pi-api:develop")).isEqualTo(GITHUB.refs.get("acme/pi-api:main"));
        assertThat(GITHUB.file("acme/pi-api", "main", "AGENTS.md")).as("el AGENTS.md de la metodología lleva el flujo del tablero")
                .contains("# Reglas para agentes", "## Tablero de Acme Lab", "instrucciones_rol").doesNotContainIgnoringCase("kuador");
        assertThat(GITHUB.file("acme/pi-api", "main", ".github/workflows/ci.yml")).contains("name: ci", ".specify/memory/constitution.md");
        assertThat(GITHUB.file("acme/pi-api", "main", ".specify/memory/constitution.md")).contains("Portal Pi");
        assertThat(GITHUB.file("acme/pi-api", "main", "README.md")).contains("# Portal Pi", "feature/PI-");

        // Vinculado y sincronizado desde el primer momento, con tokens de corta duración reutilizados.
        Reply sync = call("GET", "/api/projects/PI/sync", null, owner);
        assertThat(sync.status()).as(String.valueOf(sync.body())).isEqualTo(200);
        JsonNode status = sync.body();
        List<String> states = new ArrayList<>();
        status.get("entries").forEach(e -> states.add(e.get("state").asString()));
        assertThat(states).isNotEmpty().allMatch("SYNCED"::equals);
        assertThat(GITHUB.tokensIssued).isEqualTo(1);
        assertThat(call("POST", "/api/projects/PI/repository/new", Map.of("installationId", FakeGitHubCloud.INSTALLATION, "name", "otro"), owner).status())
                .as("un proyecto, un repo").isEqualTo(409);

        // Repo público: sí quedan protegidas main y develop.
        call("POST", "/api/projects", Map.of("key", "PU", "name", "Público", "templateKey", "SOFTWARE_FACTORY"), owner);
        JsonNode pub = call("POST", "/api/projects/PU/repository/new", Map.of("installationId", FakeGitHubCloud.INSTALLATION, "name", "pu-web",
                "privateRepo", false), owner).body();
        assertThat(pub.get("warnings")).isEmpty();
        assertThat(GITHUB.protectedBranches).containsExactly("acme/pu-web:main", "acme/pu-web:develop");

        // Quitar la conexión del espacio.
        assertThat(call("DELETE", "/api/github/installations/" + FakeGitHubCloud.INSTALLATION, null, owner).status()).isEqualTo(204);
        assertThat(call("GET", "/api/github", null, owner).body().get("connections")).isEmpty();
    }
}
