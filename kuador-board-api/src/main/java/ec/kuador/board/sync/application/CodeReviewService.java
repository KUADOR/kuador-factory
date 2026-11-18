package ec.kuador.board.sync.application;

import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.sync.CodeReviewApi;
import ec.kuador.board.sync.domain.CodeHost;
import ec.kuador.board.sync.domain.ComponentRepository;
import ec.kuador.board.sync.domain.ProjectComponent;
import ec.kuador.board.sync.domain.RepositoryLink;
import ec.kuador.board.sync.domain.SyncRecordRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lectura del código del proyecto en GitHub para los agentes del tablero. Solo los repos vinculados al proyecto: el
 * de documentos (con su credencial) y los de sus piezas (con la conexión de GitHub del espacio).
 */
@Service
public class CodeReviewService implements CodeReviewApi {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewService.class);
    private static final int MAX_PULLS = 15;

    private final SyncRecordRepository records;
    private final ComponentRepository components;
    private final List<CodeHost> hosts;

    CodeReviewService(SyncRecordRepository records, ComponentRepository components, List<CodeHost> hosts) {
        this.records = records;
        this.components = components;
        this.hosts = hosts;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> repositories(String projectKey) {
        return List.copyOf(access(projectKey).keySet());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PullSummary> pullRequests(String projectKey, String search) {
        String needle = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
        List<PullSummary> all = new ArrayList<>();
        for (CodeHost.Access a : access(projectKey).values()) {
            try {
                all.addAll(host(a).pulls(a));
            } catch (DomainException e) {
                log.info("No se pudieron listar los PRs de {}: {}", a.repository(), e.getMessage());
            }
        }
        return all.stream()
                .filter(p -> needle.isEmpty() || contains(p.title(), needle) || contains(p.branch(), needle) || contains(p.body(), needle))
                .sorted(Comparator.comparing(PullSummary::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_PULLS)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String merge(String projectKey, String repository, int number, String headSha, String title) {
        CodeHost.Access a = require(projectKey, repository);
        return host(a).merge(a, number, headSha, title);
    }

    @Override
    @Transactional(readOnly = true)
    public PullDetail pullRequest(String projectKey, String repository, int number) {
        CodeHost.Access a = require(projectKey, repository);
        return host(a).pull(a, number).orElseThrow(() -> new DomainException("No existe el PR #" + number + " en " + a.repository() + "."));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> tree(String projectKey, String repository, int max) {
        CodeHost.Access a = require(projectKey, repository);
        return host(a).tree(a, null, max).stream().filter(p -> !secretLooking(p)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String file(String projectKey, String repository, String ref, String path) {
        if (path == null || path.isBlank() || path.contains("..") || path.startsWith("/")) throw new DomainException("Ruta no permitida: " + path);
        if (secretLooking(path)) throw new DomainException("Por seguridad los agentes no leen archivos de secretos o credenciales: " + path);
        CodeHost.Access a = require(projectKey, repository);
        return host(a).file(a, ref, path.strip())
                .orElseThrow(() -> new DomainException("No existe " + path + (ref == null || ref.isBlank() ? "" : " en " + ref) + " en " + a.repository() + "."));
    }

    private static final java.util.regex.Pattern SECRET_FILE = java.util.regex.Pattern.compile(
            "(?i)^(\\.env(\\..*)?|.*\\.(pem|key|p12|pfx|jks|keystore|tfvars|tfstate)|id_(rsa|dsa|ecdsa|ed25519)(\\.pub)?|\\.npmrc|\\.pypirc|\\.netrc"
                    + "|\\.git-credentials|credentials(\\.(json|ya?ml|txt|csv|properties))?|.*secrets?\\.(ya?ml|json|env|txt|properties|toml)|application-prod.*\\.(ya?ml|properties)|service-account.*\\.json)$");

    /**
     * Archivos que suelen guardar secretos: un agente no los lee aunque un comentario se lo pida (prompt injection). Las
     * plantillas ({@code .env.example}, {@code *.sample}, {@code *.template}) sí.
     */
    static boolean secretLooking(String path) {
        String p = path.strip().replace('\\', '/');
        if (p.startsWith(".git/") || p.contains("/.git/")) return true;
        String name = p.substring(p.lastIndexOf('/') + 1);
        if (name.matches("(?i).*\\.(example|sample|template|dist)$")) return false;
        return SECRET_FILE.matcher(name).matches();
    }

    /** Repos del proyecto y cómo llegar a cada uno, sin repetir (el nombre en minúsculas es la clave). */
    private Map<String, CodeHost.Access> access(String projectKey) {
        Map<String, CodeHost.Access> out = new LinkedHashMap<>();
        Optional<RepositoryLink> link = records.findLink(projectKey);
        link.filter(l -> supported(l.kind())).ifPresent(l -> out.put(l.location(), new CodeHost.Access(l.location(), l.kind(), l.secret())));
        for (ProjectComponent c : components.findByProject(projectKey)) {
            if (c.repoLocation() == null || c.installationId() == null || out.containsKey(c.repoLocation())) continue;
            out.put(c.repoLocation(), new CodeHost.Access(c.repoLocation(), ComponentService.KIND, String.valueOf(c.installationId())));
        }
        return out;
    }

    private CodeHost.Access require(String projectKey, String repository) {
        String wanted = repository == null ? "" : repository.strip();
        Map<String, CodeHost.Access> all = access(projectKey);
        if (wanted.isEmpty() && all.size() == 1) return all.values().iterator().next();
        return all.values().stream().filter(a -> a.repository().equalsIgnoreCase(wanted)).findFirst()
                .orElseThrow(() -> new DomainException("«" + wanted + "» no es un repositorio de este proyecto. Los suyos: "
                        + (all.isEmpty() ? "ninguno" : String.join(", ", all.keySet())) + "."));
    }

    private boolean supported(String kind) {
        return hosts.stream().anyMatch(h -> h.supports(kind));
    }

    private CodeHost host(CodeHost.Access a) {
        return hosts.stream().filter(h -> h.supports(a.kind())).findFirst()
                .orElseThrow(() -> new DomainException("No se puede leer " + a.repository() + " desde el tablero."));
    }

    /** {@code needle} como palabra: «med-1» no coincide con «med-12» ni con «xmed-1». */
    static boolean contains(String text, String needle) {
        if (text == null) return false;
        return java.util.regex.Pattern.compile("(?<![\\p{Alnum}])" + java.util.regex.Pattern.quote(needle) + "(?![\\p{Alnum}])")
                .matcher(text.toLowerCase(Locale.ROOT)).find();
    }
}
