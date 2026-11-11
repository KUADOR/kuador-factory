package ec.kuador.board.sync.infrastructure.github;

import ec.kuador.board.shared.crypto.SecretBox;
import ec.kuador.board.shared.github.GitHubClient;

import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.sync.domain.RepositoryFiles;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Un repositorio de GitHub visto desde su rama principal. Lee con la API de Git (un solo árbol
 * recursivo + blobs) y publica creando una rama, un commit con todos los archivos y un Pull Request.
 * Una instancia vive lo que dura una petición: cachea el árbol y los blobs leídos.
 */
final class GitHubFiles implements RepositoryFiles {

    private static final Logger log = LoggerFactory.getLogger(GitHubFiles.class);
    private static final Pattern SAFE_FILE = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*\\.md$");
    private static final long MAX_BYTES = 1_000_000;
    private static final DateTimeFormatter BRANCH_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final GitHubClient client;
    private final String repo;
    private final String branch;
    private Map<String, String> tree; // ruta → sha del blob, en la rama principal
    private final Map<String, Optional<String>> contents = new HashMap<>();

    GitHubFiles(GitHubClient client, String repo, String branch) {
        this.client = client;
        this.repo = repo;
        this.branch = branch;
    }

    @Override
    public Optional<String> read(String path) {
        safe(path);
        return contents.computeIfAbsent(path, p -> {
            String sha = tree().get(p);
            if (sha == null) return Optional.empty();
            JsonNode blob = client.get("/repos/" + repo + "/git/blobs/" + sha);
            if (blob.path("size").asLong() > MAX_BYTES) {
                throw new DomainException(p + " pesa más de 1 MB; no se sincroniza.");
            }
            byte[] bytes = Base64.getMimeDecoder().decode(blob.path("content").asString(""));
            try {
                return Optional.of(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
            } catch (CharacterCodingException e) {
                throw new DomainException(p + " no está en UTF-8.");
            }
        });
    }

    @Override
    public List<String> listMarkdown(String directory, int depth) {
        String prefix = directory.isEmpty() ? "" : directory + "/";
        return tree().keySet().stream()
                .filter(p -> p.startsWith(prefix) && SAFE_FILE.matcher(p).matches())
                .filter(p -> p.substring(prefix.length()).split("/").length <= depth)
                .sorted()
                .toList();
    }

    @Override
    public Published publish(List<FileWrite> files, String commitMessage, String title, String description, String existingBranch) {
        files.forEach(f -> safe(f.path()));
        if (existingBranch != null) {
            Optional<String> head = refSha(existingBranch);
            if (head.isPresent()) {
                String commit = commit(files, commitMessage, head.get());
                GitData.advance(client, repo, existingBranch, commit);
                log.info("Commit {} agregado al PR del tablero en {}:{}", commit, repo, existingBranch);
                return new Published(false, null, null, existingBranch);
            }
            log.info("La rama {} ya no existe en {}; se abre un PR nuevo", existingBranch, repo);
        }
        String base = refSha(branch).orElseThrow(() -> new DomainException("La rama " + branch + " no existe en " + repo + "."));
        String commit = commit(files, commitMessage, base);
        String newBranch = "tablero/docs-" + LocalDateTime.now(ZoneId.of("America/Guayaquil")).format(BRANCH_TIME)
                + "-" + UUID.randomUUID().toString().substring(0, 4);
        GitData.createBranch(client, repo, newBranch, commit);
        JsonNode pr = client.post("/repos/" + repo + "/pulls", GitHubClient.JSON.createObjectNode()
                .put("title", title).put("head", newBranch).put("base", branch).put("body", description));
        log.info("PR #{} abierto en {} desde {}", pr.path("number").asInt(), repo, newBranch);
        return new Published(false, pr.path("number").asInt(), pr.path("html_url").asString(), newBranch);
    }

    @Override
    public Optional<PullRequestState> pullRequestState(int number) {
        return client.find("/repos/" + repo + "/pulls/" + number).map(pr -> {
            if (pr.path("merged").asBoolean(false)) return PullRequestState.MERGED;
            return "closed".equals(pr.path("state").asString("")) ? PullRequestState.CLOSED : PullRequestState.OPEN;
        });
    }

    private String commit(List<FileWrite> files, String message, String parent) {
        return GitData.commit(client, repo, files.stream().map(f -> new GitData.Entry(f.path(), f.content())).toList(), message, parent);
    }

    private Optional<String> refSha(String ref) {
        return GitData.refSha(client, repo, ref);
    }

    private Map<String, String> tree() {
        if (tree == null) {
            String head = refSha(branch).orElseThrow(() -> new DomainException("La rama " + branch + " no existe en " + repo + "."));
            String treeSha = client.get("/repos/" + repo + "/git/commits/" + head).path("tree").path("sha").asString();
            JsonNode res = client.get("/repos/" + repo + "/git/trees/" + treeSha + "?recursive=1");
            if (res.path("truncated").asBoolean(false)) {
                log.warn("El árbol de {} es demasiado grande y GitHub lo truncó; algunos documentos podrían no verse", repo);
            }
            Map<String, String> map = new HashMap<>();
            for (JsonNode e : res.path("tree")) {
                if ("blob".equals(e.path("type").asString(""))) map.put(e.path("path").asString(), e.path("sha").asString());
            }
            tree = map;
        }
        return tree;
    }

    private static void safe(String path) {
        if (path == null || path.contains("..") || !SAFE_FILE.matcher(path).matches()) {
            throw new DomainException("Ruta no permitida: " + path);
        }
    }

}
