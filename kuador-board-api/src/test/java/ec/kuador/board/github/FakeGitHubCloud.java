package ec.kuador.board.github;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GitHub falso para la GitHub App: manifiesto, JWT (verifica la firma con la clave pública de la app),
 * OAuth de quien instala, tokens de instalación, creación de repos con la API de Git y protección de
 * ramas (rechazada en repos privados, como en el plan gratuito).
 */
final class FakeGitHubCloud implements AutoCloseable {

    static final long APP_ID = 777;
    static final long INSTALLATION = 42;
    static final String ORG = "acme";
    static final String MANIFEST_CODE = "codigo-manifiesto";
    static final String OAUTH_CODE = "codigo-oauth";
    static final String INSTALLATION_TOKEN = "ghs_instalacion";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final KeyPair keys;
    private final AtomicInteger ids = new AtomicInteger();
    /** Instalaciones que administra la persona que vuelve de GitHub (su token OAuth). */
    final List<Long> userInstallations = new ArrayList<>(List.of(INSTALLATION));
    final Map<String, Map<String, Object>> repos = new LinkedHashMap<>();
    final Map<String, String> refs = new HashMap<>(); // "repo:branch" → commit
    final Map<String, Map<String, String>> trees = new HashMap<>(); // tree → ruta → contenido
    final Map<String, String> commitTree = new HashMap<>();
    final List<String> protectedBranches = new ArrayList<>();
    final Map<String, String> modes = new HashMap<>(); // "tree:ruta" → modo (100755 = ejecutable)
    final Map<String, String> blobs = new HashMap<>(); // blob → base64
    final List<Map<String, String>> pulls = new ArrayList<>();
    final List<String> requests = new ArrayList<>();
    int tokensIssued;

    FakeGitHubCloud() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keys = gen.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Repo plantilla con archivos en la rama {@code ref}. Los binarios van como "b64:..."; {@code executables} con modo 100755. */
    void seed(String full, boolean privateRepo, String ref, Map<String, String> files, List<String> executables) {
        repos.put(full, new LinkedHashMap<>(Map.of("full_name", full, "html_url", "https://github.com/" + full, "default_branch", ref, "private", privateRepo)));
        String tree = "t" + ids.incrementAndGet();
        trees.put(tree, new HashMap<>(files));
        executables.forEach(path -> modes.put(tree + ":" + path, "100755"));
        String commit = "c" + ids.incrementAndGet();
        commitTree.put(commit, tree);
        refs.put(full + ":" + ref, commit);
    }

    String mode(String repo, String branch, String path) {
        return modes.getOrDefault(commitTree.get(refs.get(repo + ":" + branch)) + ":" + path, "100644");
    }

    /** Archivo en la punta de una rama. */
    String file(String repo, String branch, String path) {
        return trees.get(commitTree.get(refs.get(repo + ":" + branch))).get(path);
    }

    /** La clave como la entrega GitHub: PKCS#1 («BEGIN RSA PRIVATE KEY»). */
    private String pkcs1Pem() {
        byte[] pkcs8 = keys.getPrivate().getEncoded();
        // PKCS#8 = SEQ { INT 0, SEQ algId, OCTET STRING pkcs1 }: la clave PKCS#1 es el último elemento.
        int i = 0;
        i++; // SEQ
        i += lengthBytes(pkcs8, i);
        i += 3; // INT 0
        i += 2 + (pkcs8[i + 1] & 0xff); // algId (largo corto)
        i++; // OCTET STRING
        int lenStart = i;
        int len = readLength(pkcs8, i);
        i += lengthBytes(pkcs8, lenStart);
        byte[] pkcs1 = java.util.Arrays.copyOfRange(pkcs8, i, i + len);
        return "-----BEGIN RSA PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pkcs1) + "\n-----END RSA PRIVATE KEY-----\n";
    }

    private static int lengthBytes(byte[] der, int at) {
        int first = der[at] & 0xff;
        return first < 0x80 ? 1 : 1 + (first & 0x7f);
    }

    private static int readLength(byte[] der, int at) {
        int first = der[at] & 0xff;
        if (first < 0x80) return first;
        int n = 0;
        for (int k = 1; k <= (first & 0x7f); k++) n = (n << 8) | (der[at + k] & 0xff);
        return n;
    }

    private boolean validJwt(String auth) throws Exception {
        if (auth == null || !auth.startsWith("Bearer ")) return false;
        String[] parts = auth.substring(7).split("\\.");
        if (parts.length != 3) return false;
        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initVerify(keys.getPublic());
        rsa.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!rsa.verify(Base64.getUrlDecoder().decode(parts[2]))) return false;
        JsonNode payload = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        return payload.path("iss").asString().equals(String.valueOf(APP_ID)) && payload.path("exp").asLong() > Instant.now().getEpochSecond();
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(method + " " + path);
        try {
            if (method.equals("POST") && path.equals("/app-manifests/" + MANIFEST_CODE + "/conversions")) {
                reply(ex, 201, Map.of("id", APP_ID, "slug", "tablero-acme", "name", "Tablero Acme", "owner", Map.of("login", ORG),
                        "html_url", "https://github.com/apps/tablero-acme", "client_id", "Iv1.cliente", "client_secret", "secreto-cliente",
                        "pem", pkcs1Pem(), "webhook_secret", "secreto-webhook"));
            } else if (method.equals("POST") && path.equals("/login/oauth/access_token")) {
                boolean ok = body.contains("code=" + OAUTH_CODE) && body.contains("client_secret=secreto-cliente");
                reply(ex, 200, ok ? Map.of("access_token", "token-usuario") : Map.of("error", "bad_verification_code", "error_description", "código vencido"));
            } else if (method.equals("GET") && path.equals("/user/installations")) {
                if (!"Bearer token-usuario".equals(auth)) { reply(ex, 401, Map.of("message", "Bad credentials")); return; }
                reply(ex, 200, Map.of("installations", userInstallations.stream().map(id -> Map.of("id", id)).toList()));
            } else if (path.startsWith("/app/installations/")) {
                if (!validJwt(auth)) { reply(ex, 401, Map.of("message", "A JSON web token could not be decoded")); return; }
                if (path.endsWith("/access_tokens")) {
                    tokensIssued++;
                    reply(ex, 201, Map.of("token", INSTALLATION_TOKEN, "expires_at", Instant.now().plusSeconds(3600).toString()));
                } else {
                    reply(ex, 200, Map.of("id", INSTALLATION, "account", Map.of("login", ORG, "type", "Organization")));
                }
            } else if (!("Bearer " + INSTALLATION_TOKEN).equals(auth)) {
                reply(ex, 401, Map.of("message", "Bad credentials"));
            } else {
                git(ex, method, path, body);
            }
        } catch (Exception e) {
            reply(ex, 500, Map.of("message", String.valueOf(e)));
        }
    }

    private void git(HttpExchange ex, String method, String path, String body) throws IOException {
        JsonNode req = body.isBlank() ? json.createObjectNode() : json.readTree(body);
        if (method.equals("POST") && path.equals("/orgs/" + ORG + "/repos")) {
            String full = ORG + "/" + req.path("name").asString();
            if (repos.containsKey(full)) { reply(ex, 422, Map.of("message", "Repository creation failed.")); return; }
            Map<String, Object> repo = new LinkedHashMap<>(Map.of("full_name", full, "html_url", "https://github.com/" + full,
                    "default_branch", "main", "private", req.path("private").asBoolean()));
            repos.put(full, repo);
            String tree = "t" + ids.incrementAndGet();
            trees.put(tree, new HashMap<>(Map.of("README.md", "# " + req.path("name").asString())));
            String commit = "c" + ids.incrementAndGet();
            commitTree.put(commit, tree);
            refs.put(full + ":main", commit);
            reply(ex, 201, repo);
            return;
        }
        String[] p = path.split("/"); // "", repos, owner, name, ...
        if (p.length < 4 || !p[1].equals("repos")) { reply(ex, 404, Map.of("message", "Not Found")); return; }
        String full = p[2] + "/" + p[3];
        Map<String, Object> repo = repos.get(full);
        if (repo == null) { reply(ex, 404, Map.of("message", "Not Found")); return; }
        String rest = String.join("/", java.util.Arrays.copyOfRange(p, 4, p.length));
        if (rest.isEmpty() && method.equals("GET")) {
            reply(ex, 200, repo);
        } else if (rest.isEmpty() && method.equals("PATCH")) {
            repo.put("default_branch", req.path("default_branch").asString());
            reply(ex, 200, repo);
        } else if (rest.startsWith("git/ref/heads/") && method.equals("GET")) {
            String sha = refs.get(full + ":" + rest.substring("git/ref/heads/".length()));
            if (sha == null) reply(ex, 404, Map.of("message", "Not Found"));
            else reply(ex, 200, Map.of("object", Map.of("sha", sha)));
        } else if (rest.startsWith("git/commits/") && method.equals("GET")) {
            reply(ex, 200, Map.of("tree", Map.of("sha", commitTree.get(rest.substring("git/commits/".length())))));
        } else if (rest.startsWith("git/trees/") && method.equals("GET")) {
            String ref = rest.substring("git/trees/".length());
            // Como GitHub: acepta el sha de un árbol o el nombre de una rama o etiqueta.
            String tree = trees.containsKey(ref) ? ref : commitTree.get(refs.get(full + ":" + ref));
            if (tree == null) { reply(ex, 404, Map.of("message", "Not Found")); return; }
            List<Map<String, Object>> entries = trees.get(tree).keySet().stream()
                    .map(file -> Map.<String, Object>of("path", file, "type", "blob", "mode", modes.getOrDefault(tree + ":" + file, "100644"),
                            "sha", tree + "~" + Base64.getUrlEncoder().withoutPadding().encodeToString(file.getBytes(StandardCharsets.UTF_8))))
                    .toList();
            reply(ex, 200, Map.of("tree", entries, "truncated", false));
        } else if (rest.startsWith("git/blobs/") && method.equals("GET")) {
            String[] sha = rest.substring("git/blobs/".length()).split("~");
            String content = trees.get(sha[0]).get(new String(Base64.getUrlDecoder().decode(sha[1]), StandardCharsets.UTF_8));
            byte[] bytes = content.startsWith("b64:") ? Base64.getDecoder().decode(content.substring(4)) : content.getBytes(StandardCharsets.UTF_8);
            reply(ex, 200, Map.of("content", Base64.getEncoder().encodeToString(bytes), "size", bytes.length));
        } else if (rest.equals("git/blobs") && method.equals("POST")) {
            String blob = "blob" + ids.incrementAndGet();
            blobs.put(blob, req.path("content").asString());
            reply(ex, 201, Map.of("sha", blob));
        } else if (rest.equals("git/trees") && method.equals("POST")) {
            String base = req.path("base_tree").asString();
            Map<String, String> files = new HashMap<>(trees.get(base));
            String tree = "t" + ids.incrementAndGet();
            modes.entrySet().stream().filter(m -> m.getKey().startsWith(base + ":")).toList()
                    .forEach(m -> modes.put(tree + m.getKey().substring(base.length()), m.getValue()));
            req.path("tree").forEach(e -> {
                String file = e.path("path").asString();
                files.put(file, e.has("sha") ? "b64:" + blobs.get(e.path("sha").asString()) : e.path("content").asString());
                modes.put(tree + ":" + file, e.path("mode").asString("100644"));
            });
            trees.put(tree, files);
            reply(ex, 201, Map.of("sha", tree));
        } else if (rest.equals("git/commits") && method.equals("POST")) {
            String commit = "c" + ids.incrementAndGet();
            commitTree.put(commit, req.path("tree").asString());
            reply(ex, 201, Map.of("sha", commit));
        } else if (rest.startsWith("git/refs/heads/") && method.equals("PATCH")) {
            refs.put(full + ":" + rest.substring("git/refs/heads/".length()), req.path("sha").asString());
            reply(ex, 200, Map.of());
        } else if (rest.equals("git/refs") && method.equals("POST")) {
            refs.put(full + ":" + req.path("ref").asString().replace("refs/heads/", ""), req.path("sha").asString());
            reply(ex, 201, Map.of());
        } else if (rest.equals("pulls") && method.equals("POST")) {
            int number = pulls.size() + 1;
            pulls.add(Map.of("repo", full, "head", req.path("head").asString(), "base", req.path("base").asString(), "title", req.path("title").asString()));
            reply(ex, 201, Map.of("number", number, "html_url", "https://github.com/" + full + "/pull/" + number));
        } else if (rest.matches("branches/.+/protection") && method.equals("PUT")) {
            if ((Boolean) repo.get("private")) {
                reply(ex, 403, Map.of("message", "Upgrade to GitHub Pro or make this repository public to enable this feature."));
            } else {
                protectedBranches.add(full + ":" + p[5]);
                reply(ex, 200, Map.of());
            }
        } else {
            reply(ex, 404, Map.of("message", "Not Found"));
        }
    }

    private void reply(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
