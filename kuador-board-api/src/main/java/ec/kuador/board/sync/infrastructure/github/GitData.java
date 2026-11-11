package ec.kuador.board.sync.infrastructure.github;

import ec.kuador.board.shared.github.GitHubClient;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Operaciones de la API de Git de GitHub que comparten la publicación de documentos y la creación de repos. */
final class GitData {

    /** Un archivo a escribir (cualquier tipo de texto). */
    record Entry(String path, String content) {}

    private GitData() {}

    static Optional<String> refSha(GitHubClient client, String repo, String branch) {
        return client.find("/repos/" + repo + "/git/ref/heads/" + branch).map(r -> r.path("object").path("sha").asString());
    }

    /** Un commit con todos los archivos sobre {@code parent}: árbol nuevo apoyado en el del padre. */
    static String commit(GitHubClient client, String repo, List<Entry> files, String message, String parent) {
        String baseTree = client.get("/repos/" + repo + "/git/commits/" + parent).path("tree").path("sha").asString();
        ArrayNode entries = GitHubClient.JSON.createArrayNode();
        for (Entry f : files) {
            entries.addObject().put("path", f.path()).put("mode", "100644").put("type", "blob").put("content", f.content());
        }
        ObjectNode treeReq = GitHubClient.JSON.createObjectNode().put("base_tree", baseTree);
        treeReq.set("tree", entries);
        String newTree = client.post("/repos/" + repo + "/git/trees", treeReq).path("sha").asString();
        ObjectNode commitReq = GitHubClient.JSON.createObjectNode().put("message", message).put("tree", newTree);
        commitReq.putArray("parents").add(parent);
        return client.post("/repos/" + repo + "/git/commits", commitReq).path("sha").asString();
    }

    /** Mueve la rama al commit (sin forzar). */
    static void advance(GitHubClient client, String repo, String branch, String commit) {
        client.patch("/repos/" + repo + "/git/refs/heads/" + branch, GitHubClient.JSON.createObjectNode().put("sha", commit).put("force", false));
    }

    static void createBranch(GitHubClient client, String repo, String branch, String sha) {
        client.post("/repos/" + repo + "/git/refs", GitHubClient.JSON.createObjectNode().put("ref", "refs/heads/" + branch).put("sha", sha));
    }
}
