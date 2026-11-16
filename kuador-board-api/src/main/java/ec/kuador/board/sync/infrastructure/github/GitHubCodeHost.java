package ec.kuador.board.sync.infrastructure.github;

import ec.kuador.board.github.GitHubApi;
import ec.kuador.board.shared.crypto.SecretBox;
import ec.kuador.board.shared.github.GitHubClient;
import ec.kuador.board.sync.CodeReviewApi.ChangedFile;
import ec.kuador.board.sync.CodeReviewApi.Check;
import ec.kuador.board.sync.CodeReviewApi.PullDetail;
import ec.kuador.board.sync.CodeReviewApi.PullSummary;
import ec.kuador.board.sync.domain.CodeHost;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Lee Pull Requests, CI y archivos con la API REST de GitHub, con el token del repo vinculado (GITHUB) o un token de
 * instalación de la GitHub App (GITHUB_APP). Recorta los diffs y archivos largos para que quepan en el contexto del
 * agente.
 */
@Component
class GitHubCodeHost implements CodeHost {

    static final int MAX_PATCH = 6_000;
    static final int MAX_PATCHES_TOTAL = 60_000;
    static final int MAX_FILE = 60_000;
    private static final int MAX_BODY = 3_000;
    private static final int MAX_SUMMARY = 600;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String apiUrl;
    private final GitHubApi github;
    private final SecretBox secrets;

    GitHubCodeHost(@Value("${board.github.api-url:https://api.github.com}") String apiUrl, GitHubApi github, SecretBox secrets) {
        this.apiUrl = apiUrl;
        this.github = github;
        this.secrets = secrets;
    }

    @Override
    public boolean supports(String kind) {
        return GitHubAppRepositoryProvider.KIND.equals(kind) || "GITHUB".equals(kind);
    }

    @Override
    public List<PullSummary> pulls(Access access) {
        JsonNode list = client(access).get("/repos/" + access.repository() + "/pulls?state=all&sort=updated&direction=desc&per_page=30");
        List<PullSummary> out = new ArrayList<>();
        for (JsonNode pr : list) out.add(summary(access.repository(), pr));
        return out;
    }

    @Override
    public Optional<PullDetail> pull(Access access, int number) {
        GitHubClient client = client(access);
        String repo = access.repository();
        Optional<JsonNode> found = client.find("/repos/" + repo + "/pulls/" + number);
        if (found.isEmpty()) return Optional.empty();
        JsonNode pr = found.get();
        String sha = pr.path("head").path("sha").asString("");

        List<ChangedFile> files = new ArrayList<>();
        int budget = MAX_PATCHES_TOTAL;
        boolean truncated = false;
        for (JsonNode f : client.get("/repos/" + repo + "/pulls/" + number + "/files?per_page=100")) {
            String patch = f.path("patch").isMissingNode() || f.path("patch").isNull() ? null : f.path("patch").asString();
            if (patch != null && patch.length() > MAX_PATCH) {
                patch = patch.substring(0, MAX_PATCH) + "\n… (diff recortado; lee el archivo completo con read_repo_file)";
                truncated = true;
            }
            if (patch != null && patch.length() > budget) {
                patch = null;
                truncated = true;
            }
            if (patch != null) budget -= patch.length();
            files.add(new ChangedFile(f.path("filename").asString(), f.path("status").asString(""), f.path("additions").asInt(),
                    f.path("deletions").asInt(), patch));
        }
        if (pr.path("changed_files").asInt(files.size()) > files.size()) truncated = true;

        List<Check> checks = new ArrayList<>();
        String note = null;
        if (!sha.isEmpty()) {
            try {
                for (JsonNode run : client.get("/repos/" + repo + "/commits/" + sha + "/check-runs?per_page=50").path("check_runs")) {
                    String summary = Stream.of(run.path("output").path("title").asString(""), run.path("output").path("summary").asString(""))
                            .filter(s -> !s.isBlank()).collect(Collectors.joining(" — "));
                    checks.add(new Check(run.path("name").asString(), run.path("status").asString(""), text(run.path("conclusion")), cut(summary, MAX_SUMMARY)));
                }
                for (JsonNode st : client.get("/repos/" + repo + "/commits/" + sha + "/status").path("statuses")) {
                    checks.add(new Check(st.path("context").asString(), "completed", st.path("state").asString(""),
                            cut(st.path("description").asString(""), MAX_SUMMARY)));
                }
            } catch (GitHubClient.Forbidden e) {
                note = "La conexión con GitHub no tiene permiso para leer el CI. Quien administra la GitHub App debe darle lectura de "
                        + "«Checks» y «Commit statuses» en GitHub (configuración de la app → Permissions) y aceptar el cambio en la instalación.";
            }
        }
        return Optional.of(new PullDetail(summary(repo, pr), sha, files, truncated, checks, note));
    }

    @Override
    public Optional<String> file(Access access, String ref, String path) {
        String query = ref == null || ref.isBlank() ? "" : "?ref=" + URLEncoder.encode(ref.strip(), StandardCharsets.UTF_8);
        String encoded = String.join("/", Stream.of(path.split("/")).map(p -> URLEncoder.encode(p, StandardCharsets.UTF_8).replace("+", "%20")).toList());
        return client(access).find("/repos/" + access.repository() + "/contents/" + encoded + query).map(node -> {
            if (!"file".equals(node.path("type").asString(""))) return "«" + path + "» no es un archivo (es una carpeta o un enlace).";
            byte[] bytes = Base64.getMimeDecoder().decode(node.path("content").asString(""));
            if (bytes.length == 0 && node.path("size").asLong() > 0) return "«" + path + "» es demasiado grande para leerlo desde el tablero.";
            String content;
            try {
                content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                return "«" + path + "» es un archivo binario.";
            }
            return content.length() > MAX_FILE ? content.substring(0, MAX_FILE) + "\n… (archivo recortado)" : content;
        });
    }

    private static PullSummary summary(String repo, JsonNode pr) {
        return new PullSummary(repo, pr.path("number").asInt(), pr.path("title").asString(""), pr.path("head").path("ref").asString(""),
                pr.path("base").path("ref").asString(""), pr.path("state").asString(""), !pr.path("merged_at").isNull() && !pr.path("merged_at").isMissingNode(),
                pr.path("user").path("login").asString(""), pr.path("html_url").asString(""), cut(text(pr.path("body")), MAX_BODY),
                text(pr.path("updated_at")));
    }

    private GitHubClient client(Access access) {
        String token = "GITHUB".equals(access.kind()) ? secrets.decrypt(access.secret()) : github.installationToken(Long.parseLong(access.secret()));
        return new GitHubClient(http, apiUrl, token);
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asString();
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
