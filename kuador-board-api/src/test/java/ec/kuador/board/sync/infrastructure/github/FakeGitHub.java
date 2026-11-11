package ec.kuador.board.sync.infrastructure.github;

import ec.kuador.board.shared.github.GitHubClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * GitHub falso en memoria con los endpoints de la API Git que usa el adaptador (refs, commits,
 * árboles, blobs, pulls). Exige el token y permite simular la fusión de un PR.
 */
final class FakeGitHub implements AutoCloseable {

    static final String TOKEN = "github_pat_bueno";
    static final String REPO = "kuador/kuador-api";

    final Map<String, String> blobs = new HashMap<>();
    final Map<String, Map<String, String>> trees = new HashMap<>();
    final Map<String, String[]> commits = new HashMap<>(); // sha → {tree, parent, message}
    final Map<String, String> refs = new LinkedHashMap<>();
    final Map<Integer, Map<String, Object>> pulls = new LinkedHashMap<>();
    final List<String> requests = new ArrayList<>();
    boolean canPush = true;
    private final AtomicInteger ids = new AtomicInteger();
    private final HttpServer server;

    FakeGitHub(Map<String, String> mainFiles) throws IOException {
        Map<String, String> tree = new HashMap<>();
        mainFiles.forEach((path, content) -> tree.put(path, blob(content)));
        String treeSha = id("tree");
        trees.put(treeSha, tree);
        String commit = id("commit");
        commits.put(commit, new String[] {treeSha, null, "inicial"});
        refs.put("main", commit);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Contenido de un archivo en la punta de una rama. */
    String file(String branch, String path) {
        String blobSha = trees.get(commits.get(refs.get(branch))[0]).get(path);
        return blobSha == null ? null : blobs.get(blobSha);
    }

    void merge(int number) {
        Map<String, Object> pr = pulls.get(number);
        refs.put((String) pr.get("base"), refs.get((String) pr.get("head")));
        pr.put("state", "closed");
        pr.put("merged", true);
    }

    void commitToMain(String path, String content) {
        String parent = refs.get("main");
        Map<String, String> tree = new HashMap<>(trees.get(commits.get(parent)[0]));
        tree.put(path, blob(content));
        String treeSha = id("tree");
        trees.put(treeSha, tree);
        String commit = id("commit");
        commits.put(commit, new String[] {treeSha, parent, "otro dev"});
        refs.put("main", commit);
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        requests.add(method + " " + path);
        if (!("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            reply(ex, 401, "{\"message\":\"Bad credentials\"}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode in = body.isBlank() ? null : GitHubClient.JSON.readTree(body);
        String base = "/repos/" + REPO;
        Matcher m;
        if (method.equals("GET") && path.equals(base)) {
            reply(ex, 200, json(o -> {
                o.put("full_name", REPO).put("default_branch", "main");
                o.putObject("permissions").put("push", canPush).put("pull", true);
            }));
        } else if (path.startsWith("/repos/") && !path.startsWith(base)) {
            reply(ex, 404, "{\"message\":\"Not Found\"}");
        } else if (method.equals("GET") && (m = match(base + "/git/ref/heads/(.+)", path)) != null) {
            String sha = refs.get(m.group(1));
            if (sha == null) reply(ex, 404, "{\"message\":\"Not Found\"}");
            else reply(ex, 200, json(o -> o.putObject("object").put("sha", sha)));
        } else if (method.equals("GET") && (m = match(base + "/git/commits/(.+)", path)) != null) {
            String tree = commits.get(m.group(1))[0];
            reply(ex, 200, json(o -> o.putObject("tree").put("sha", tree)));
        } else if (method.equals("GET") && (m = match(base + "/git/trees/(.+)", path)) != null) {
            Map<String, String> tree = trees.get(m.group(1));
            reply(ex, 200, json(o -> {
                ArrayNode arr = o.putArray("tree");
                tree.forEach((p, sha) -> arr.addObject().put("path", p).put("type", "blob").put("sha", sha));
                o.put("truncated", false);
            }));
        } else if (method.equals("GET") && (m = match(base + "/git/blobs/(.+)", path)) != null) {
            byte[] content = blobs.get(m.group(1)).getBytes(StandardCharsets.UTF_8);
            reply(ex, 200, json(o -> o.put("content", Base64.getMimeEncoder().encodeToString(content)).put("encoding", "base64")
                    .put("size", content.length)));
        } else if (method.equals("POST") && path.equals(base + "/git/trees")) {
            Map<String, String> tree = new HashMap<>(trees.get(in.path("base_tree").asString()));
            for (JsonNode e : in.path("tree")) tree.put(e.path("path").asString(), blob(e.path("content").asString()));
            String sha = id("tree");
            trees.put(sha, tree);
            reply(ex, 201, json(o -> o.put("sha", sha)));
        } else if (method.equals("POST") && path.equals(base + "/git/commits")) {
            String sha = id("commit");
            commits.put(sha, new String[] {in.path("tree").asString(), in.path("parents").get(0).asString(), in.path("message").asString()});
            reply(ex, 201, json(o -> o.put("sha", sha)));
        } else if (method.equals("POST") && path.equals(base + "/git/refs")) {
            refs.put(in.path("ref").asString().replace("refs/heads/", ""), in.path("sha").asString());
            reply(ex, 201, "{}");
        } else if (method.equals("PATCH") && (m = match(base + "/git/refs/heads/(.+)", path)) != null) {
            refs.put(m.group(1), in.path("sha").asString());
            reply(ex, 200, "{}");
        } else if (method.equals("POST") && path.equals(base + "/pulls")) {
            int number = pulls.size() + 1;
            Map<String, Object> pr = new HashMap<>();
            pr.put("head", in.path("head").asString());
            pr.put("base", in.path("base").asString());
            pr.put("title", in.path("title").asString());
            pr.put("body", in.path("body").asString());
            pr.put("state", "open");
            pr.put("merged", false);
            pulls.put(number, pr);
            reply(ex, 201, json(o -> o.put("number", number).put("html_url", "https://github.com/" + REPO + "/pull/" + number)));
        } else if (method.equals("GET") && (m = match(base + "/pulls/(\\d+)", path)) != null) {
            Map<String, Object> pr = pulls.get(Integer.parseInt(m.group(1)));
            if (pr == null) reply(ex, 404, "{\"message\":\"Not Found\"}");
            else reply(ex, 200, json(o -> o.put("state", (String) pr.get("state")).put("merged", (Boolean) pr.get("merged"))));
        } else {
            reply(ex, 404, "{\"message\":\"Not Found\"}");
        }
    }

    private String blob(String content) {
        String sha = id("blob");
        blobs.put(sha, content);
        return sha;
    }

    private String id(String prefix) {
        return prefix + ids.incrementAndGet();
    }

    private static Matcher match(String regex, String path) {
        Matcher m = Pattern.compile("^" + regex + "$").matcher(path);
        return m.matches() ? m : null;
    }

    private static String json(java.util.function.Consumer<ObjectNode> fill) {
        ObjectNode o = GitHubClient.JSON.createObjectNode();
        fill.accept(o);
        return o.toString();
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
