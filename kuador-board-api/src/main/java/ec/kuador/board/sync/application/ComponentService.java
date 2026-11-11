package ec.kuador.board.sync.application;

import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.ComponentTemplate;
import ec.kuador.board.docs.DocsApi;
import ec.kuador.board.docs.DocsApi.DocContent;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.sync.RepositoriesApi;
import ec.kuador.board.sync.RepositoryInstructions;
import ec.kuador.board.sync.domain.ComponentRepository;
import ec.kuador.board.sync.domain.ProjectComponent;
import ec.kuador.board.sync.domain.RepositoryCreator;
import ec.kuador.board.sync.domain.RepositoryCreator.File;
import ec.kuador.board.sync.domain.RepositoryLink;
import ec.kuador.board.sync.domain.SyncRecord;
import ec.kuador.board.sync.domain.SyncRecordRepository;
import ec.kuador.board.sync.domain.SyncState;
import ec.kuador.board.sync.domain.TemplateFiles;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Piezas de un proyecto (API, web, microservicios, microfrontends...) y sus repos en GitHub.
 *
 * <p>Al armar el proyecto se crea el <em>repo del proyecto</em> con sus bases y documentos; cada pieza
 * va en una carpeta de ese repo (monorepo) o en un repo propio (un repo por pieza). Después se pueden
 * sumar piezas: una carpeta llega por Pull Request a {@code develop}; un repo propio se crea con sus bases.
 */
@Service
public class ComponentService implements RepositoriesApi {

    private static final Logger log = LoggerFactory.getLogger(ComponentService.class);
    static final String KIND = "GITHUB_APP";
    static final String WORK_BRANCH = "develop";
    private static final Pattern PIECE = Pattern.compile("^[a-z][a-z0-9-]{1,39}$");

    /** Una pieza a crear. {@code repoName} nulo = carpeta en el repo del proyecto; si no, un repo propio. */
    public record PieceSpec(String name, String templateKey, String repoName, Map<String, String> variables) {}

    /** Armado inicial: el repo del proyecto (bases y documentos) y sus piezas. */
    public record Setup(long installationId, String repoName, String description, boolean privateRepo, List<PieceSpec> pieces) {}

    /** Un repo que tocó el tablero y lo que GitHub no permitió en él. */
    public record RepoResult(String repository, String url, List<String> protectedBranches, List<String> warnings, String pullRequestUrl) {}

    public record Result(RepositoryLink link, List<RepoResult> repositories, List<ProjectComponent> pieces, int documents) {}

    private final ComponentRepository components;
    private final SyncRecordRepository records;
    private final RepositoryCreator creator;
    private final TemplateFiles templates;
    private final CatalogApi catalog;
    private final DocsApi docs;
    private final ProjectApi projects;
    private final RepositoryInstructions agents;
    private final Clock clock;

    ComponentService(ComponentRepository components, SyncRecordRepository records, RepositoryCreator creator, TemplateFiles templates,
            CatalogApi catalog, DocsApi docs, ProjectApi projects, RepositoryInstructions agents, Clock clock) {
        this.components = components;
        this.records = records;
        this.creator = creator;
        this.templates = templates;
        this.catalog = catalog;
        this.docs = docs;
        this.projects = projects;
        this.agents = agents;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Piece> pieces(String projectKey) {
        return components.findByProject(projectKey).stream()
                .map(c -> new Piece(c.name(), c.kind(), c.stack(), c.templateKey(), c.repoLocation(), c.path(), c.buildCommand(), c.testCommand(), c.runCommand()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProjectComponent> list(String projectKey) {
        software(projectKey);
        return components.findByProject(projectKey);
    }

    // ---- Armado inicial ----

    @Transactional
    public Result setup(String projectKey, Setup s, UUID actorId) {
        ProjectView project = software(projectKey);
        if (records.findLink(projectKey).isPresent()) {
            throw new ConflictException("El proyecto ya tiene un repositorio. Suma piezas desde «Agregar pieza».");
        }
        List<PieceSpec> specs = s.pieces() == null ? List.of() : s.pieces();
        Set<String> names = new HashSet<>();
        Set<String> repos = new HashSet<>(Set.of(s.repoName() == null ? "" : s.repoName().strip()));
        for (PieceSpec p : specs) {
            validName(p.name());
            if (!names.add(p.name())) throw new DomainException("Hay dos piezas llamadas «" + p.name() + "».");
            if (p.repoName() != null && !p.repoName().isBlank() && !repos.add(p.repoName().strip())) {
                throw new DomainException("El repo «" + p.repoName().strip() + "» está repetido: cada pieza con repo propio necesita uno distinto.");
            }
        }
        String agentsMd = agents.agentsMd();
        List<DocContent> documents = projectDocuments(projectKey, agentsMd, actorId);

        // Piezas en carpeta: van en el commit inicial del repo del proyecto.
        List<Prepared> inFolder = new ArrayList<>();
        List<Prepared> ownRepo = new ArrayList<>();
        for (PieceSpec p : specs) {
            boolean own = p.repoName() != null && !p.repoName().isBlank();
            Prepared prepared = prepare(project, p, own ? "" : p.name(), s.installationId());
            (own ? ownRepo : inFolder).add(prepared);
        }
        List<RepositoryBase.PieceLine> lines = new ArrayList<>();
        inFolder.forEach(p -> lines.add(p.line(p.spec().name() + "/")));
        ownRepo.forEach(p -> lines.add(p.line("repo " + p.spec().repoName().strip())));

        List<File> projectFiles = new ArrayList<>(RepositoryBase.files(project.name(), projectKey, s.description(), agentsMd, documents, lines));
        List<Prepared> all = new ArrayList<>(inFolder);
        all.addAll(ownRepo);
        projectFiles.addAll(kit(all));
        inFolder.forEach(p -> projectFiles.addAll(p.files()));
        RepositoryCreator.Created main = creator.create(
                new RepositoryCreator.NewRepository(s.installationId(), s.repoName(), s.description(), s.privateRepo(), checks(inFolder)),
                dedupe(projectFiles), "chore: bases del proyecto " + projectKey + " desde el tablero");
        Instant now = clock.instant();
        RepositoryLink link = new RepositoryLink(projectKey, KIND, main.fullName(), main.workBranch(), String.valueOf(s.installationId()), now, actorId);
        records.saveLink(link);
        documents.forEach(d -> records.saveRecord(new SyncRecord(d.id(), d.version(), SyncState.sha256(d.content()), now)));

        List<RepoResult> results = new ArrayList<>(List.of(result(main, null)));
        List<ProjectComponent> saved = new ArrayList<>();
        inFolder.forEach(p -> saved.add(save(projectKey, p, main.fullName(), s.installationId(), actorId, now)));
        for (Prepared p : ownRepo) {
            RepositoryCreator.Created repo = createPieceRepo(project, p, s.installationId(), s.privateRepo(), main.fullName(), agentsMd);
            results.add(result(repo, null));
            saved.add(save(projectKey, p, repo.fullName(), s.installationId(), actorId, now));
        }
        log.info("Proyecto {} armado: {} pieza(s), {} repo(s)", projectKey, saved.size(), results.size());
        return new Result(link, results, saved, documents.size());
    }

    // ---- Sumar una pieza después ----

    @Transactional
    public Result add(String projectKey, PieceSpec spec, boolean privateRepo, UUID actorId) {
        ProjectView project = software(projectKey);
        RepositoryLink link = records.findLink(projectKey).filter(l -> KIND.equals(l.kind()))
                .orElseThrow(() -> new DomainException("Primero arma el repo del proyecto con la app de GitHub."));
        validName(spec.name());
        if (components.findByProject(projectKey).stream().anyMatch(c -> c.name().equals(spec.name()))) {
            throw new ConflictException("El proyecto ya tiene una pieza «" + spec.name() + "».");
        }
        long installationId = Long.parseLong(link.secret());
        boolean own = spec.repoName() != null && !spec.repoName().isBlank();
        Prepared p = prepare(project, spec, own ? "" : spec.name(), installationId);
        Instant now = clock.instant();
        RepoResult result;
        ProjectComponent saved;
        if (own) {
            RepositoryCreator.Created repo = createPieceRepo(project, p, installationId, privateRepo, link.location(), agents.agentsMd());
            result = result(repo, null);
            saved = save(projectKey, p, repo.fullName(), installationId, actorId, now);
        } else {
            RepositoryCreator.Proposal pr = creator.propose(installationId, link.location(), WORK_BRANCH, "tablero/pieza-" + spec.name(), dedupe(p.files()),
                    "feat(" + projectKey + "): agrega la pieza " + spec.name(),
                    "Pieza **" + spec.name() + "** (" + p.stack() + ") creada desde el tablero" + (p.template() == null ? "." : " con la plantilla " + p.template().name() + "."));
            result = new RepoResult(link.location(), null, List.of(), List.of(), pr.url());
            saved = save(projectKey, p, link.location(), installationId, actorId, now);
        }
        return new Result(link, List.of(result), List.of(saved), 0);
    }

    // ---- Apoyo ----

    /** Pieza lista para escribir: su plantilla (si tiene) ya leída y con las variables aplicadas. */
    private record Prepared(PieceSpec spec, ComponentTemplate template, String kind, String stack, String folder, List<File> files) {

        RepositoryBase.PieceLine line(String location) {
            ComponentTemplate.Commands c = template == null ? null : template.commands();
            return new RepositoryBase.PieceLine(spec.name(), stack, location, c == null ? null : c.build(), c == null ? null : c.test(), c == null ? null : c.run());
        }
    }

    private Prepared prepare(ProjectView project, PieceSpec spec, String folder, long installationId) {
        ComponentTemplate template = spec.templateKey() == null || spec.templateKey().isBlank() ? null
                : catalog.componentTemplate(spec.templateKey())
                        .orElseThrow(() -> new DomainException("No existe la plantilla de pieza " + spec.templateKey() + " en el catálogo."));
        Map<String, String> values = TemplateRenderer.values(template, spec.variables(), project.name(), project.key(), spec.name(), folder);
        List<File> code = template != null && template.templateRepo() != null && !template.templateRepo().isBlank()
                ? TemplateRenderer.render(templates.read(installationId, template.templateRepo().strip(), template.templateRef()), values, folder)
                : List.of();
        String kind = template == null ? ComponentTemplate.Kind.OTHER.name() : template.kind().name();
        String stack = template == null ? "Por definir" : template.stack();
        List<File> files = new ArrayList<>();
        String readme = folder.isEmpty() ? "README.md" : folder + "/README.md";
        Prepared draft = new Prepared(spec, template, kind, stack, folder, List.of());
        // El README de la pieza: el de la plantilla si trae uno; si no, uno con su stack y comandos.
        if (code.stream().noneMatch(f -> f.path().equals(readme)) && !folder.isEmpty()) {
            files.add(new File(readme, RepositoryBase.pieceReadme(project.name(), project.key(), null, draft.line(folder + "/"))));
        }
        files.addAll(code);
        return new Prepared(spec, template, kind, stack, folder, files);
    }

    /** Repo propio de una pieza: bases de pieza y, encima, el código de su plantilla (gana la plantilla). */
    private RepositoryCreator.Created createPieceRepo(ProjectView project, Prepared p, long installationId, boolean privateRepo, String projectRepo, String agentsMd) {
        List<File> files = new ArrayList<>(RepositoryBase.pieceRepoFiles(project.name(), project.key(), agentsMd, projectRepo, p.line(".")));
        files.addAll(kit(List.of(p)));
        files.addAll(p.files());
        return creator.create(new RepositoryCreator.NewRepository(installationId, p.spec().repoName().strip(), project.name() + " · " + p.spec().name(),
                privateRepo, checks(List.of(p))), dedupe(files), "chore: pieza " + p.spec().name() + " del proyecto " + project.key() + " desde el tablero");
    }

    /** Kit de agentes del repo con los servidores MCP que sugieren las plantillas de sus piezas. */
    private List<File> kit(List<Prepared> pieces) {
        return agents.agentKit(mcpServers(pieces.stream().map(Prepared::template).toList())).stream()
                .map(f -> new File(f.path(), f.content())).toList();
    }

    static List<String> mcpServers(List<ComponentTemplate> templates) {
        return templates.stream().filter(java.util.Objects::nonNull).flatMap(t -> t.mcpServers().stream()).distinct().toList();
    }

    /** Verificaciones de CI que exigen las ramas protegidas: «ci» del tablero más las de cada plantilla. */
    private static List<String> checks(List<Prepared> pieces) {
        Set<String> checks = new LinkedHashSet<>(List.of("ci"));
        pieces.stream().filter(p -> p.template() != null).forEach(p -> checks.addAll(p.template().ciChecks()));
        return List.copyOf(checks);
    }

    /** Si dos archivos tienen la misma ruta, gana el último (la plantilla sobre las bases). */
    private static List<File> dedupe(List<File> files) {
        Map<String, File> byPath = new LinkedHashMap<>();
        files.forEach(f -> byPath.put(f.path(), f));
        return List.copyOf(byPath.values());
    }

    private List<DocContent> projectDocuments(String projectKey, String agentsMd, UUID actorId) {
        String heading = agentsMd.lines().findFirst().orElse("");
        return docs.ensureProjectDocuments(projectKey, actorId).stream()
                .map(d -> d.path().equals(RepositoryBase.AGENTS_MD) && !d.content().contains(heading)
                        ? docs.write(projectKey, null, d.kind(), RepositoryBase.withBoardSection(d.content(), agentsMd), "Agrega cómo trabajar con el tablero", actorId)
                        : d)
                .toList();
    }

    private ProjectComponent save(String projectKey, Prepared p, String repository, long installationId, UUID actorId, Instant now) {
        ComponentTemplate t = p.template();
        ProjectComponent c = new ProjectComponent(UUID.randomUUID(), projectKey, p.spec().name(), t == null ? null : t.key(), t == null ? null : t.version(),
                t == null ? null : t.templateRepo(), t == null ? null : t.templateRef(), p.kind(), p.stack(),
                t == null ? null : t.commands().build(), t == null ? null : t.commands().test(), t == null ? null : t.commands().run(),
                repository, p.folder(), installationId, actorId, now);
        components.save(c);
        return c;
    }

    private static RepoResult result(RepositoryCreator.Created c, String pr) {
        return new RepoResult(c.fullName(), c.htmlUrl(), c.protectedBranches(), c.warnings(), pr);
    }

    private static void validName(String name) {
        if (name == null || !PIECE.matcher(name).matches()) {
            throw new DomainException("El nombre de la pieza va en minúsculas, números o guiones (p. ej. api, web, pagos).");
        }
    }

    private ProjectView software(String projectKey) {
        ProjectView project = projects.find(projectKey).orElseThrow(() -> new NotFoundException("No existe el proyecto " + projectKey + "."));
        if (!project.software()) throw new DomainException("Las piezas y repositorios son para proyectos de software.");
        return project;
    }
}
