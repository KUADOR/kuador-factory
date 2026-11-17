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
                new SignupService.Company("Acme Lab", null, null, null, null, null), "Ana", "clave-de-ana-123", "FACTORY");
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
        assertThat(manifest.get("redirect_url").asString()).isEqualTo("http://web.test/connections/github/app-created");
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

        // Una plantilla de pieza propia de la empresa, con su repo de código base en una versión fija.
        GITHUB.seed("acme/plantilla-api", true, "v1", Map.of(
                "README.md", "# {{PIEZA}} de {{PROYECTO}}",
                "src/main/java/App.java", "package {{PAQUETE}};",
                "mvnw", "#!/bin/sh",
                "logo.png", "b64:iVBORw0KGgo=",
                ".github/workflows/ci.yml", "working-directory: {{PIEZA_RUTA}}"), List.of("mvnw"));
        Map<String, Object> template = new java.util.LinkedHashMap<>();
        template.put("key", "API_ACME");
        template.put("name", "API Acme");
        template.put("description", "API con el código base de Acme");
        template.put("kind", "BACKEND");
        template.put("stack", "Java 25 · Spring Boot");
        template.put("templateRepo", "acme/plantilla-api");
        template.put("templateRef", "v1");
        template.put("variables", List.of(Map.of("name", "PAQUETE", "description", "Paquete", "defaultValue", "com.acme")));
        template.put("commands", Map.of("build", "./mvnw package", "test", "./mvnw verify", "run", "./mvnw spring-boot:run"));
        template.put("ciChecks", List.of("ci"));
        template.put("suggestedRoles", List.of("BACKEND"));
        template.put("mcpServers", List.of());
        template.put("builtIn", false);
        template.put("version", 0);
        assertThat(call("PUT", "/api/catalog/component-templates/API_ACME", template, owner).status()).isEqualTo(200);

        // Monorepo privado: API con su plantilla y web Angular (sin código base todavía), cada una en su carpeta.
        call("POST", "/api/projects", Map.of("key", "PI", "name", "Portal Pi", "templateKey", "SOFTWARE_FACTORY"), owner);
        call("POST", "/api/projects/PI/people", Map.of("memberId", dev.id().toString()), owner);
        call("PUT", "/api/projects/PI/methodology", Map.of("methodology", "SDD"), owner);
        Reply mono = call("POST", "/api/projects/PI/components/setup", Map.of("installationId", FakeGitHubCloud.INSTALLATION, "repoName", "pi",
                "description", "Portal de pacientes", "pieces", List.of(
                        Map.of("name", "api", "templateKey", "API_ACME", "variables", Map.of("PAQUETE", "ec.pi.api")),
                        Map.of("name", "web", "templateKey", "WEB_ANGULAR"))), owner);
        assertThat(mono.status()).as(String.valueOf(mono.body())).isEqualTo(201);
        assertThat(mono.body().get("link").get("kind").asString()).isEqualTo("GITHUB_APP");
        assertThat(mono.body().get("link").get("branch").asString()).isEqualTo("develop");
        assertThat(mono.body().get("repositories")).hasSize(1);
        assertThat(mono.body().get("repositories").get(0).get("warnings").get(0).asString()).contains("plan gratuito", "Upgrade to GitHub Pro");
        assertThat(mono.body().get("pieces")).hasSize(2);
        assertThat(GITHUB.repos.get("acme/pi").get("default_branch")).isEqualTo("develop");
        assertThat(GITHUB.file("acme/pi", "main", "api/src/main/java/App.java")).isEqualTo("package ec.pi.api;");
        assertThat(GITHUB.file("acme/pi", "main", "api/README.md")).as("el README de la plantilla").isEqualTo("# api de Portal Pi");
        assertThat(GITHUB.mode("acme/pi", "main", "api/mvnw")).isEqualTo("100755");
        assertThat(GITHUB.file("acme/pi", "main", "api/logo.png")).as("binario intacto").isEqualTo("b64:iVBORw0KGgo=");
        assertThat(GITHUB.file("acme/pi", "main", ".github/workflows/api-ci.yml")).isEqualTo("working-directory: api");
        assertThat(GITHUB.file("acme/pi", "main", "web/README.md")).contains("Angular", "npm run build");
        assertThat(GITHUB.file("acme/pi", "main", "README.md")).contains("## Piezas", "| api | Java 25 · Spring Boot | `api/` |", "| web |");
        assertThat(GITHUB.file("acme/pi", "main", "AGENTS.md")).as("el AGENTS.md de la metodología lleva el flujo del tablero")
                .contains("# Reglas para agentes", "## Tablero de Acme Lab", "role_manual").doesNotContainIgnoringCase("kuador");
        assertThat(GITHUB.file("acme/pi", "main", ".specify/memory/constitution.md")).contains("Portal Pi");
        assertThat(call("GET", "/api/projects/PI/components", null, devSession).body()).extracting(n -> n.get("path").asString()).containsExactly("api", "web");

        // El equipo ajusta la pieza creada: va con Java 21 y Spring Boot 3, y deja una nota para los agentes.
        assertThat(call("PUT", "/api/projects/PI/components/api", Map.of("stack", "Java 21 · Spring Boot 3", "build", "./mvnw -B package",
                "test", "./mvnw -B verify", "notes", "Nada de APIs de Java 22+."), devSession).status()).as("solo quien administra").isEqualTo(403);
        Reply adjusted = call("PUT", "/api/projects/PI/components/api", Map.of("stack", "Java 21 · Spring Boot 3", "build", "./mvnw -B package",
                "test", "./mvnw -B verify", "notes", "Nada de APIs de Java 22+."), owner);
        assertThat(adjusted.status()).isEqualTo(200);
        JsonNode api = call("GET", "/api/projects/PI/components", null, devSession).body().get(0);
        assertThat(api.get("stack").asString()).isEqualTo("Java 21 · Spring Boot 3");
        assertThat(api.get("notes").asString()).isEqualTo("Nada de APIs de Java 22+.");
        assertThat(api.get("run").isNull()).as("sin comando").isTrue();

        // Vinculado y sincronizado desde el primer momento, con tokens de corta duración reutilizados.
        Reply sync = call("GET", "/api/projects/PI/sync", null, owner);
        assertThat(sync.status()).as(String.valueOf(sync.body())).isEqualTo(200);
        List<String> states = new ArrayList<>();
        sync.body().get("entries").forEach(e -> states.add(e.get("state").asString()));
        assertThat(states).isNotEmpty().allMatch("SYNCED"::equals);
        assertThat(GITHUB.tokensIssued).isEqualTo(1);
        assertThat(call("POST", "/api/projects/PI/components/setup", Map.of("installationId", FakeGitHubCloud.INSTALLATION, "repoName", "otro"), owner).status())
                .as("se arma una sola vez").isEqualTo(409);

        // El kit de agentes nace en el repo, sin secretos: cada persona solo inicia sesión desde su cliente.
        assertThat(GITHUB.file("acme/pi", "develop", ".mcp.json")).contains("\"acme-lab\"", "/mcp").doesNotContain("Authorization");
        assertThat(GITHUB.file("acme/pi", "develop", ".claude/commands/pendientes.md")).contains("mcp__acme-lab__pending_work");
        assertThat(GITHUB.file("acme/pi", "develop", ".claude/agents/acme-lab-backend.md")).contains("agent: BACKEND", "el código va en inglés");
        assertThat(GITHUB.file("acme/pi", "develop", ".codex/config.toml")).contains("[mcp_servers.acme-lab]", "codex mcp login acme-lab");
        JsonNode kit = call("GET", "/api/projects/PI/sync/agent-kit", null, owner).body();
        assertThat(kit).hasSize(1);
        assertThat(kit.get(0).get("files")).allSatisfy(f -> assertThat(f.get("state").asString()).isEqualTo("CURRENT"));
        assertThat(call("POST", "/api/projects/PI/sync/agent-kit", null, owner).status()).as("al día: nada que proponer").isEqualTo(422);

        // El equipo decide escribir el código en español: el kit queda desactualizado y se propone por PR (uno solo).
        assertThat(call("PUT", "/api/workspace/languages", Map.of("team", "es", "code", "es"), owner).status()).isEqualTo(200);
        JsonNode outdated = call("GET", "/api/projects/PI/sync/agent-kit", null, owner).body().get(0).get("files");
        assertThat(outdated).anySatisfy(f -> assertThat(f.get("state").asString()).isEqualTo("OUTDATED"));
        Reply proposed = call("POST", "/api/projects/PI/sync/agent-kit", null, owner);
        assertThat(proposed.status()).as(String.valueOf(proposed.body())).isEqualTo(200);
        assertThat(proposed.body().get(0).get("pullRequest").get("url").asString()).contains("acme/pi/pull/");
        int pulls = GITHUB.pulls.size();
        String kitBranch = proposed.body().get(0).get("pullRequest").get("branch").asString();
        assertThat(kitBranch).startsWith("tablero/kit-agentes");
        assertThat(GITHUB.file("acme/pi", kitBranch, ".claude/agents/acme-lab-backend.md")).contains("todo va en español");
        call("PUT", "/api/workspace/languages", Map.of("team", "es", "code", "en"), owner);
        call("POST", "/api/projects/PI/sync/agent-kit", null, owner);
        assertThat(GITHUB.pulls).as("suma un commit al PR abierto").hasSize(pulls);
        assertThat(GITHUB.file("acme/pi", kitBranch, ".claude/agents/acme-lab-backend.md")).contains("el código va en inglés");

        // Sumar una pieza después en el monorepo: llega por Pull Request a develop.
        Reply worker = call("POST", "/api/projects/PI/components", Map.of("piece", Map.of("name", "worker", "templateKey", "MICROSERVICE_SPRING")), owner);
        assertThat(worker.status()).as(String.valueOf(worker.body())).isEqualTo(201);
        assertThat(worker.body().get("repositories").get(0).get("pullRequestUrl").asString()).contains("acme/pi/pull/");
        assertThat(GITHUB.pulls).anySatisfy(pr -> assertThat(pr).containsEntry("head", "tablero/pieza-worker").containsEntry("base", "develop"));
        assertThat(GITHUB.file("acme/pi", "tablero/pieza-worker", "worker/README.md")).contains("Spring Boot");

        // Un repo por pieza, públicos: el repo del proyecto guarda los documentos; cada pieza en la raíz de su repo, protegido.
        call("POST", "/api/projects", Map.of("key", "PU", "name", "Pagos", "templateKey", "SOFTWARE_FACTORY"), owner);
        Reply multi = call("POST", "/api/projects/PU/components/setup", Map.of("installationId", FakeGitHubCloud.INSTALLATION, "repoName", "pu-plataforma",
                "privateRepo", false, "pieces", List.of(
                        Map.of("name", "pagos", "templateKey", "API_ACME", "repoName", "pu-pagos"),
                        Map.of("name", "shell", "templateKey", "SHELL_MICROFRONTENDS", "repoName", "pu-shell"))), owner);
        assertThat(multi.status()).as(String.valueOf(multi.body())).isEqualTo(201);
        assertThat(multi.body().get("repositories")).extracting(r -> r.get("repository").asString())
                .containsExactly("acme/pu-plataforma", "acme/pu-pagos", "acme/pu-shell");
        assertThat(multi.body().get("repositories")).allSatisfy(r -> assertThat(r.get("warnings")).isEmpty());
        assertThat(GITHUB.protectedBranches).contains("acme/pu-pagos:main", "acme/pu-pagos:develop", "acme/pu-shell:main", "acme/pu-plataforma:main");
        assertThat(GITHUB.file("acme/pu-pagos", "main", "src/main/java/App.java")).isEqualTo("package com.acme;");
        assertThat(GITHUB.file("acme/pu-pagos", "main", ".github/workflows/ci.yml")).as("gana el CI de la plantilla").isEqualTo("working-directory: .");
        assertThat(GITHUB.file("acme/pu-shell", "main", "README.md")).contains("Pagos · shell", "acme/pu-plataforma");
        assertThat(GITHUB.file("acme/pu-plataforma", "main", "README.md")).contains("repo pu-pagos", "repo pu-shell");

        // Sumar después un microfrontend en su propio repo.
        Reply mf = call("POST", "/api/projects/PU/components", Map.of("piece", Map.of("name", "checkout", "templateKey", "MICROFRONTEND_REACT",
                "repoName", "pu-mf-checkout"), "privateRepo", false), owner);
        assertThat(mf.status()).as(String.valueOf(mf.body())).isEqualTo(201);
        assertThat(GITHUB.repos).containsKey("acme/pu-mf-checkout");
        assertThat(call("GET", "/api/projects/PU/components", null, owner).body()).extracting(n -> n.get("repository").asString())
                .containsExactly("acme/pu-pagos", "acme/pu-shell", "acme/pu-mf-checkout");

        // Quitar la conexión del espacio.
        assertThat(call("DELETE", "/api/github/installations/" + FakeGitHubCloud.INSTALLATION, null, owner).status()).isEqualTo(204);
        assertThat(call("GET", "/api/github", null, owner).body().get("connections")).isEmpty();
    }
}
