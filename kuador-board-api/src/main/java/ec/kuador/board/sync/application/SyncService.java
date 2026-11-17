package ec.kuador.board.sync.application;

import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.Methodology;
import ec.kuador.board.docs.DocsApi;
import ec.kuador.board.docs.DocsApi.DocContent;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.sync.RepositoryInstructions;
import ec.kuador.board.sync.domain.ExportPullRequest;
import ec.kuador.board.sync.domain.RepoLayout;
import ec.kuador.board.sync.domain.RepositoryFiles;
import ec.kuador.board.sync.domain.RepositoryFiles.PullRequestState;
import ec.kuador.board.sync.domain.RepositoryLink;
import ec.kuador.board.sync.domain.RepositoryProvider;
import ec.kuador.board.sync.domain.SyncRecord;
import ec.kuador.board.sync.domain.SyncRecordRepository;
import ec.kuador.board.sync.domain.SyncState;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sincroniza los documentos de un proyecto con su repositorio. El repositorio se ve desde su rama
 * principal: importar trae lo fusionado; exportar abre un Pull Request en GitHub. Nunca resuelve un conflicto por su cuenta: exige {@code force} (una persona eligió).
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final SyncRecordRepository records;
    private final Map<String, RepositoryProvider> providers;
    private final DocsApi docs;
    private final ProjectApi projects;
    private final IssueApi issues;
    private final CatalogApi catalog;
    private final RepositoryInstructions instructions;
    private final Clock clock;

    SyncService(SyncRecordRepository records, List<RepositoryProvider> providers, DocsApi docs, ProjectApi projects, IssueApi issues,
            CatalogApi catalog, RepositoryInstructions instructions, Clock clock) {
        this.records = records;
        this.providers = providers.stream().collect(Collectors.toMap(RepositoryProvider::kind, Function.identity()));
        this.docs = docs;
        this.projects = projects;
        this.issues = issues;
        this.catalog = catalog;
        this.instructions = instructions;
        this.clock = clock;
    }

    /** PR abierto que lleva este documento, y qué versión exportó. */
    public record OpenPr(int number, String url, int version) {}

    public record Entry(DocContent document, SyncState state, boolean fileExists, OpenPr openPr) {}

    /** Archivos del repo que el tablero aún no tiene, agrupados (una carpeta de spec = un grupo). */
    public record Untracked(String group, boolean issueScoped, List<UntrackedFile> files, String suggestedTitle) {}

    public record UntrackedFile(String path, String kind) {}

    public record Status(RepositoryLink link, List<Entry> entries, List<Untracked> untracked, List<ExportPullRequest> openPullRequests) {}

    public record Comparison(DocContent document, String fileContent, SyncState state) {}

    /** Los documentos quedaron en el Pull Request {@code pullRequestUrl}. */
    public record Exported(int documents, Integer pullRequest, String pullRequestUrl) {}

    public record SafeResult(int imported, int conflicts, Exported exported) {}

    // ---- Vínculo ----

    @Transactional(readOnly = true)
    public Optional<RepositoryLink> link(String projectKey) {
        software(projectKey);
        return records.findLink(projectKey);
    }

    @Transactional
    public RepositoryLink link(String projectKey, String kind, RepositoryProvider.LinkSpec spec, UUID actorId) {
        software(projectKey);
        RepositoryProvider.Validated v = provider(kind).validate(spec);
        Optional<RepositoryLink> previous = records.findLink(projectKey);
        boolean sameRepo = previous.isPresent() && previous.get().kind().equals(kind)
                && previous.get().location().equals(v.location()) && Objects.equals(previous.get().branch(), v.branch());
        if (!sameRepo) {
            forgetHistory(projectKey); // otro repositorio: la historia anterior no aplica
        }
        RepositoryLink link = new RepositoryLink(projectKey, kind, v.location(), v.branch(), v.secret(), clock.instant(), actorId);
        records.saveLink(link);
        log.info("Proyecto {} vinculado a {} {}{} por {}", projectKey, kind, v.location(), v.branch() == null ? "" : "@" + v.branch(), actorId);
        return link;
    }

    @Transactional
    public void unlink(String projectKey) {
        software(projectKey);
        forgetHistory(projectKey);
        records.deleteLink(projectKey);
    }

    // ---- Estado ----

    /**
     * Estado de cada documento. Antes revisa los PRs que abrió el tablero: si uno se fusionó, sus
     * documentos quedan sincronizados con la versión que llevaba (por eso no es de solo lectura).
     */
    @Transactional
    public Status status(String projectKey) {
        return status(projectKey, null);
    }

    /** {@code actorId}: quien mira el estado; firma la fusión automática del AGENTS.md (null = no fusiona). */
    @Transactional
    public Status status(String projectKey, UUID actorId) {
        RepositoryLink link = requireLink(projectKey);
        RepositoryFiles files = open(link);
        List<ExportPullRequest> open = reconcile(projectKey, files);
        List<DocContent> all = docs.ofProject(projectKey);
        Map<UUID, SyncRecord> last = new HashMap<>(records.findRecords(all.stream().map(DocContent::id).toList()));
        if (actorId != null) all = all.stream().map(d -> withBoardSection(projectKey, d, last, files, actorId)).toList();
        List<Entry> entries = all.stream().map(d -> {
            Optional<String> file = files.read(d.path());
            SyncState state = SyncState.of(Optional.ofNullable(last.get(d.id())), d.version(), d.content(), file);
            return new Entry(d, state, file.isPresent(), openPrFor(open, d.id()));
        }).toList();
        return new Status(link, entries, untracked(projectKey, files, all), open);
    }

    @Transactional(readOnly = true)
    public Comparison compare(String projectKey, UUID documentId) {
        RepositoryLink link = requireLink(projectKey);
        DocContent doc = document(projectKey, documentId);
        Optional<String> file = open(link).read(doc.path());
        return new Comparison(doc, file.orElse(null), state(doc, file));
    }

    // ---- Exportar / importar ----

    @Transactional
    public Exported export(String projectKey, UUID documentId, boolean force, UUID actorId) {
        return export(projectKey, List.of(documentId), force ? Set.of(documentId) : Set.of(), actorId);
    }

    /**
     * Publica los documentos en un solo commit. En GitHub abre un PR, o agrega el commit al PR del
     * tablero que ya esté abierto. {@code forced}: documentos en conflicto donde una persona eligió el tablero.
     */
    @Transactional
    public Exported export(String projectKey, Collection<UUID> documentIds, Set<UUID> forced, UUID actorId) {
        RepositoryLink link = requireLink(projectKey);
        RepositoryFiles files = open(link);
        List<ExportPullRequest> open = reconcile(projectKey, files);
        List<DocContent> toExport = new ArrayList<>();
        for (UUID id : documentIds) {
            DocContent doc = document(projectKey, id);
            OpenPr pr = openPrFor(open, id);
            if (pr != null && pr.version() == doc.version()) continue; // esta versión ya va en el PR
            SyncState state = state(doc, files.read(doc.path()));
            if (state == SyncState.SYNCED) continue;
            if (!state.canExport(forced.contains(id))) {
                throw conflictOrWrongDirection(state, doc.path());
            }
            toExport.add(doc);
        }
        if (toExport.isEmpty()) {
            throw new DomainException(open.isEmpty() ? "No hay cambios del tablero para exportar."
                    : "Esos cambios ya están en el PR #" + open.getFirst().number() + ": revísalo y fusiónalo en GitHub.");
        }
        ExportPullRequest existing = open.isEmpty() ? null : open.getFirst();
        String projectName = projects.name(projectKey);
        RepositoryFiles.Published published = files.publish(
                toExport.stream().map(d -> new RepositoryFiles.FileWrite(d.path(), d.content())).toList(),
                "docs(" + projectKey + "): actualiza " + toExport.size() + " documento(s) desde el tablero",
                "Documentos de " + projectName + " desde el tablero",
                description(projectName, toExport),
                existing == null ? null : existing.branch());
        Instant now = clock.instant();
        List<ExportPullRequest.Item> items = toExport.stream()
                .map(d -> new ExportPullRequest.Item(d.id(), d.version(), SyncState.sha256(d.content()))).toList();
        ExportPullRequest pr = published.pullRequest() == null && existing != null
                ? existing.withItems(items)
                : new ExportPullRequest(UUID.randomUUID(), projectKey, published.pullRequest(), published.url(), published.branch(),
                        PullRequestState.OPEN, now, actorId, items);
        if (existing != null && published.pullRequest() != null) {
            records.savePullRequest(existing.withState(PullRequestState.CLOSED)); // su rama ya no existía
        }
        records.savePullRequest(pr);
        return new Exported(toExport.size(), pr.number(), pr.url());
    }

    /** Guarda el contenido del archivo (en la rama principal) como versión nueva del documento. */
    @Transactional
    public Entry importFile(String projectKey, UUID documentId, boolean force, UUID actorId) {
        RepositoryLink link = requireLink(projectKey);
        RepositoryFiles files = open(link);
        DocContent doc = document(projectKey, documentId);
        Optional<String> file = files.read(doc.path());
        SyncState state = state(doc, file);
        if (state == SyncState.SYNCED) return new Entry(doc, state, true, null);
        if (file.isEmpty() || !state.canImport(force)) {
            throw conflictOrWrongDirection(state, doc.path());
        }
        DocContent saved = docs.saveFromRepository(doc.id(), doc.version(), file.get(), "Importado de " + doc.path(), actorId);
        records.saveRecord(new SyncRecord(saved.id(), saved.version(), SyncState.sha256(file.get()), clock.instant()));
        return new Entry(saved, SyncState.SYNCED, true, null);
    }

    /**
     * Importa un grupo que solo existe en el repo. Un grupo de issue necesita {@code issueKey}
     * (existente) o {@code newIssueTitle} (crea el issue).
     */
    @Transactional
    public List<Entry> importNew(String projectKey, String group, String issueKey, String newIssueTitle, UUID actorId) {
        RepositoryLink link = requireLink(projectKey);
        RepositoryFiles files = open(link);
        Untracked target = untracked(projectKey, files, docs.ofProject(projectKey)).stream()
                .filter(u -> u.group().equals(group)).findFirst()
                .orElseThrow(() -> new NotFoundException("No hay archivos nuevos en " + group + " (¿ya se importaron?)."));
        String scope = null;
        if (target.issueScoped()) {
            if (issueKey != null && !issueKey.isBlank()) {
                scope = issues.find(issueKey.strip().toUpperCase()).filter(i -> i.projectKey().equals(projectKey))
                        .orElseThrow(() -> new NotFoundException("No existe el issue " + issueKey + " en " + projectKey + ".")).key();
            } else if (newIssueTitle != null && !newIssueTitle.isBlank()) {
                scope = issues.createForImport(projectKey, newIssueTitle.strip(), actorId).key();
            } else {
                throw new DomainException("Indica a qué issue pertenece " + group + " o el título del issue nuevo.");
            }
        }
        List<Entry> imported = new ArrayList<>();
        for (UntrackedFile f : target.files()) {
            String content = files.read(f.path()).orElseThrow(() -> new ConflictException(f.path() + " desapareció del repo."));
            DocContent doc = docs.createFromRepository(projectKey, f.kind(), scope, f.path(), content, actorId);
            records.saveRecord(new SyncRecord(doc.id(), doc.version(), SyncState.sha256(content), clock.instant()));
            imported.add(new Entry(doc, SyncState.SYNCED, true, null));
        }
        return imported;
    }

    /**
     * Importa todo lo que cambió solo en el repo y exporta en un único commit (un PR) lo que cambió
     * solo en el tablero. Los conflictos quedan para una persona.
     */
    @Transactional
    public SafeResult syncSafe(String projectKey, UUID actorId) {
        Status status = status(projectKey, actorId);
        int imported = 0, conflicts = 0;
        List<UUID> toExport = new ArrayList<>();
        Instant now = clock.instant();
        for (Entry e : status.entries()) {
            boolean alreadyInPr = e.openPr() != null && e.openPr().version() == e.document().version();
            switch (e.state()) {
                case BOARD_AHEAD, BOARD_ONLY -> {
                    if (!alreadyInPr) toExport.add(e.document().id());
                }
                case FILE_AHEAD -> {
                    importFile(projectKey, e.document().id(), false, actorId);
                    imported++;
                }
                case SYNCED -> {
                    // Idénticos pero sin registro (p. ej. recién vinculado): se registra para la próxima vez.
                    if (records.findRecords(List.of(e.document().id())).isEmpty()) {
                        records.saveRecord(new SyncRecord(e.document().id(), e.document().version(),
                                SyncState.sha256(e.document().content()), now));
                    }
                }
                case CONFLICT -> {
                    if (!alreadyInPr) conflicts++;
                }
                case FILE_DELETED -> { } // borrado a propósito en el repo: no se recrea sin que alguien lo pida
            }
        }
        Exported exported = toExport.isEmpty() ? null : export(projectKey, toExport, Set.of(), actorId);
        return new SafeResult(imported, conflicts, exported);
    }

    // ---- Apoyo ----

    /**
     * El AGENTS.md que el tablero puso al crear el repo y el documento «Reglas para agentes» creado después
     * apuntan al mismo archivo. Si el archivo sigue tal como lo dejó el tablero, no es un conflicto: el
     * documento suma el flujo del tablero como una sección y queda listo para exportar (nada se pierde).
     * Si alguien editó el archivo en el repo, sigue siendo un conflicto que decide una persona.
     */
    private DocContent withBoardSection(String projectKey, DocContent doc, Map<UUID, SyncRecord> last, RepositoryFiles files, UUID actorId) {
        if (!doc.path().equals(RepositoryBase.AGENTS_MD) || doc.issueKey() != null || last.containsKey(doc.id())) return doc;
        Optional<String> file = files.read(doc.path());
        String base = instructions.agentsMd();
        if (file.isEmpty() || !file.get().strip().equals(base.strip()) || doc.content().strip().equals(base.strip())) return doc;
        String heading = base.lines().findFirst().orElse("").replaceFirst("^#", "##");
        DocContent merged = doc.content().contains(heading) ? doc
                : docs.write(projectKey, null, doc.kind(), RepositoryBase.withBoardSection(doc.content(), base), "Agrega cómo trabajar con el tablero", actorId);
        // Registro: el archivo es el de las bases y el tablero va adelante desde entonces.
        SyncRecord record = new SyncRecord(doc.id(), merged.version() == doc.version() ? doc.version() - 1 : doc.version(), SyncState.sha256(file.get()), clock.instant());
        records.saveRecord(record);
        last.put(doc.id(), record);
        log.info("{}: el documento {} suma la sección del tablero del AGENTS.md de las bases", projectKey, doc.path());
        return merged;
    }

    /** Revisa los PRs abiertos: fusionado → sincroniza lo que llevaba; cerrado → se descarta. */
    private List<ExportPullRequest> reconcile(String projectKey, RepositoryFiles files) {
        List<ExportPullRequest> stillOpen = new ArrayList<>();
        Set<UUID> existing = docs.ofProject(projectKey).stream().map(DocContent::id).collect(Collectors.toSet());
        for (ExportPullRequest pr : records.findOpenPullRequests(projectKey)) {
            PullRequestState state = files.pullRequestState(pr.number()).orElse(PullRequestState.CLOSED);
            switch (state) {
                case OPEN -> stillOpen.add(pr);
                case MERGED -> {
                    pr.items().stream().filter(i -> existing.contains(i.documentId())).forEach(i ->
                            records.saveRecord(new SyncRecord(i.documentId(), i.version(), i.contentSha256(), clock.instant())));
                    records.savePullRequest(pr.withState(PullRequestState.MERGED));
                    log.info("PR #{} de {} fusionado: {} documento(s) sincronizados", pr.number(), projectKey, pr.items().size());
                }
                case CLOSED -> records.savePullRequest(pr.withState(PullRequestState.CLOSED));
            }
        }
        return stillOpen;
    }

    private static OpenPr openPrFor(List<ExportPullRequest> open, UUID documentId) {
        for (ExportPullRequest pr : open) {
            for (ExportPullRequest.Item i : pr.items()) {
                if (i.documentId().equals(documentId)) return new OpenPr(pr.number(), pr.url(), i.version());
            }
        }
        return null;
    }

    private static String description(String projectName, List<DocContent> exported) {
        StringBuilder sb = new StringBuilder("Cambios en los documentos de **").append(projectName)
                .append("** exportados desde el tablero.\n\n| Documento | Ruta | Versión |\n|---|---|---|\n");
        exported.forEach(d -> sb.append("| ").append(d.title().replace("|", "\\|")).append(" | `").append(d.path())
                .append("` | v").append(d.version()).append(" |\n"));
        return sb.append("\nAl fusionar este PR, el tablero marca estos documentos como sincronizados.").toString();
    }

    /** Disposición del repo según la metodología del proyecto (sin metodología, nada que reconocer). */
    private RepoLayout layout(String projectKey) {
        String key = projects.find(projectKey).map(ProjectView::methodology).orElse(null);
        return RepoLayout.of(catalog.methodology(key).map(Methodology::documents).orElse(List.of()));
    }

    private List<Untracked> untracked(String projectKey, RepositoryFiles files, List<DocContent> all) {
        Set<String> known = all.stream().map(DocContent::path).collect(Collectors.toSet());
        RepoLayout layout = layout(projectKey);
        Map<String, List<UntrackedFile>> groups = new LinkedHashMap<>();
        for (String path : layout.scan(files)) {
            if (known.contains(path)) continue;
            RepoLayout.Match m = layout.classify(path).orElseThrow();
            groups.computeIfAbsent(m.group(), g -> new ArrayList<>()).add(new UntrackedFile(path, m.kind()));
        }
        return groups.entrySet().stream().map(g -> {
            boolean issueScoped = layout.classify(g.getValue().getFirst().path()).orElseThrow().issueScoped();
            String main = g.getValue().stream().filter(f -> f.kind().equals("SPEC")).findFirst().orElse(g.getValue().getFirst()).path();
            return new Untracked(g.getKey(), issueScoped, g.getValue(),
                    issueScoped ? RepoLayout.suggestTitle(g.getKey(), files.read(main)) : null);
        }).toList();
    }

    private SyncState state(DocContent doc, Optional<String> file) {
        return SyncState.of(Optional.ofNullable(records.findRecords(List.of(doc.id())).get(doc.id())), doc.version(), doc.content(), file);
    }

    private static RuntimeException conflictOrWrongDirection(SyncState state, String path) {
        return switch (state) {
            case CONFLICT -> new ConflictException(path + " cambió en el tablero y en el repo. Compara ambos y elige cuál conservar.");
            case FILE_AHEAD -> new DomainException(path + ": el repo tiene cambios más nuevos; impórtalos primero.");
            case BOARD_AHEAD, BOARD_ONLY, FILE_DELETED -> new DomainException(path + ": el tablero tiene cambios más nuevos; expórtalos.");
            case SYNCED -> new DomainException(path + " ya está sincronizado.");
        };
    }

    private void forgetHistory(String projectKey) {
        records.deleteRecords(docs.ofProject(projectKey).stream().map(DocContent::id).toList());
        records.deletePullRequests(projectKey);
    }

    private RepositoryFiles open(RepositoryLink link) {
        return provider(link.kind()).open(link);
    }

    private RepositoryProvider provider(String kind) {
        RepositoryProvider p = providers.get(kind);
        if (p == null) throw new DomainException("Tipo de repositorio no soportado: " + kind + ".");
        return p;
    }

    private DocContent document(String projectKey, UUID id) {
        return docs.ofProject(projectKey).stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("No existe el documento " + id + " en " + projectKey + "."));
    }

    private RepositoryLink requireLink(String projectKey) {
        software(projectKey);
        return records.findLink(projectKey)
                .orElseThrow(() -> new DomainException("Vincula primero el repositorio del proyecto en Configurar."));
    }

    private ProjectView software(String projectKey) {
        ProjectView p = projects.find(projectKey).orElseThrow(() -> new NotFoundException("No existe el proyecto " + projectKey + "."));
        if (!p.software()) {
            throw new DomainException("La sincronización con repositorios es solo para proyectos de software.");
        }
        return p;
    }
}
