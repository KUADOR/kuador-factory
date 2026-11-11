package ec.kuador.board.sync.infrastructure.github;

import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.github.GitHubClient;
import ec.kuador.board.sync.domain.RepositoryCreator.File;
import ec.kuador.board.sync.domain.TemplateFiles;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Código base de una plantilla en GitHub: el árbol completo en la versión fijada. La app tiene que
 * poder leer el repo plantilla (estar instalada en él, o que sea público).
 */
@Component
class GitHubTemplateFiles implements TemplateFiles {

    private static final int MAX_FILES = 800;
    private static final long MAX_BYTES = 15_000_000;

    private final GitHubAppRepositoryProvider provider;

    GitHubTemplateFiles(GitHubAppRepositoryProvider provider) {
        this.provider = provider;
    }

    @Override
    public List<File> read(long installationId, String repository, String ref) {
        GitHubClient client = provider.client(installationId);
        if (client.find("/repos/" + repository).isEmpty()) {
            throw new DomainException("La app no puede leer la plantilla " + repository + ". Instálala también en ese repo o hazlo público.");
        }
        JsonNode tree = client.find("/repos/" + repository + "/git/trees/" + ref + "?recursive=1")
                .orElseThrow(() -> new DomainException("La plantilla " + repository + " no tiene la versión " + ref + "."));
        if (tree.path("truncated").asBoolean(false)) throw new DomainException("La plantilla " + repository + " es demasiado grande para copiarla.");
        List<File> files = new ArrayList<>();
        long total = 0;
        for (JsonNode e : tree.path("tree")) {
            if (!"blob".equals(e.path("type").asString(""))) continue;
            if (files.size() >= MAX_FILES) throw new DomainException("La plantilla " + repository + " tiene más de " + MAX_FILES + " archivos.");
            JsonNode blob = client.get("/repos/" + repository + "/git/blobs/" + e.path("sha").asString());
            byte[] bytes = Base64.getMimeDecoder().decode(blob.path("content").asString(""));
            total += bytes.length;
            if (total > MAX_BYTES) throw new DomainException("La plantilla " + repository + " pesa más de 15 MB.");
            boolean executable = "100755".equals(e.path("mode").asString(""));
            String path = e.path("path").asString();
            try {
                String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                files.add(new File(path, text, false, executable));
            } catch (CharacterCodingException binary) {
                files.add(new File(path, Base64.getEncoder().encodeToString(bytes), true, executable));
            }
        }
        return files;
    }
}
