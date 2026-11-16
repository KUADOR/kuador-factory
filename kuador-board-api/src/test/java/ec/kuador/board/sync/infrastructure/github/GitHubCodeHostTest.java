package ec.kuador.board.sync.infrastructure.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import ec.kuador.board.shared.crypto.SecretBox;
import ec.kuador.board.sync.CodeReviewApi;
import ec.kuador.board.sync.domain.CodeHost;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** QA en el tablero lee el PR, su diff y el CI en GitHub, sin clonar el repo. */
class GitHubCodeHostTest {

    private HttpServer server;
    private final Map<String, String> routes = new HashMap<>();
    private final Map<String, Integer> status = new HashMap<>();
    private final List<String> auth = new ArrayList<>();
    private GitHubCodeHost host;
    private CodeHost.Access access;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String key = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            String body = routes.getOrDefault(key, "{\"message\":\"Not Found\"}");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.getOrDefault(key, routes.containsKey(key) ? 200 : 404), bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        byte[] key = new byte[32];
        SecretBox secrets = new SecretBox(Base64.getEncoder().encodeToString(key));
        host = new GitHubCodeHost("http://127.0.0.1:" + server.getAddress().getPort(), null, secrets);
        access = new CodeHost.Access("acme/med", "GITHUB", secrets.encrypt("ghp_token"));

        String pr = """
                {"number":6,"title":"MED-5 CRUD de pacientes","state":"open","merged_at":null,"html_url":"https://github.com/acme/med/pull/6",
                 "body":"Cierra MED-5","user":{"login":"diego"},"head":{"ref":"feature/MED-5","sha":"abc123"},"base":{"ref":"develop"},
                 "updated_at":"2026-11-16T10:00:00Z","changed_files":2}""";
        routes.put("/repos/acme/med/pulls?state=all&sort=updated&direction=desc&per_page=30", "[" + pr + "]");
        routes.put("/repos/acme/med/pulls/6", pr);
        routes.put("/repos/acme/med/pulls/6/files?per_page=100", """
                [{"filename":"src/Paciente.java","status":"added","additions":40,"deletions":0,"patch":"@@ -0,0 +1 @@\\n+class Paciente {}"},
                 {"filename":"logo.png","status":"added","additions":0,"deletions":0}]""");
        routes.put("/repos/acme/med/commits/abc123/check-runs?per_page=50", """
                {"check_runs":[{"name":"api","status":"completed","conclusion":"failure","output":{"title":"2 pruebas fallan","summary":"PacienteTest"}}]}""");
        routes.put("/repos/acme/med/commits/abc123/status", """
                {"statuses":[{"context":"sonar","state":"success","description":"Calidad OK"}]}""");
        routes.put("/repos/acme/med/contents/src/Paciente.java?ref=feature%2FMED-5",
                "{\"type\":\"file\",\"size\":17,\"content\":\"" + Base64.getEncoder().encodeToString("class Paciente {}".getBytes()) + "\"}");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void readsThePullRequestItsDiffAndTheCi() {
        assertThat(host.pulls(access)).singleElement().satisfies(p -> {
            assertThat(p.number()).isEqualTo(6);
            assertThat(p.branch()).isEqualTo("feature/MED-5");
            assertThat(p.merged()).isFalse();
        });

        CodeReviewApi.PullDetail d = host.pull(access, 6).orElseThrow();
        assertThat(d.headSha()).isEqualTo("abc123");
        assertThat(d.files()).extracting(CodeReviewApi.ChangedFile::path).containsExactly("src/Paciente.java", "logo.png");
        assertThat(d.files().getFirst().patch()).contains("+class Paciente {}");
        assertThat(d.files().get(1).patch()).as("binario: sin diff").isNull();
        assertThat(d.checks()).extracting(CodeReviewApi.Check::name, CodeReviewApi.Check::conclusion)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("api", "failure"), org.assertj.core.groups.Tuple.tuple("sonar", "success"));
        assertThat(d.checks().getFirst().summary()).contains("2 pruebas fallan");
        assertThat(d.checksNote()).isNull();
        assertThat(auth).as("con el token del repo vinculado").allMatch("Bearer ghp_token"::equals);

        assertThat(host.file(access, "feature/MED-5", "src/Paciente.java")).hasValue("class Paciente {}");
        assertThat(host.pull(access, 99)).isEmpty();
    }

    @Test
    void withoutPermissionToReadTheCiItSaysHowToFixIt() {
        status.put("/repos/acme/med/commits/abc123/check-runs?per_page=50", 403);
        routes.put("/repos/acme/med/commits/abc123/check-runs?per_page=50", "{\"message\":\"Resource not accessible by integration\"}");

        CodeReviewApi.PullDetail d = host.pull(access, 6).orElseThrow();
        assertThat(d.files()).hasSize(2);
        assertThat(d.checks()).isEmpty();
        assertThat(d.checksNote()).contains("Checks", "Commit statuses");
    }
}
