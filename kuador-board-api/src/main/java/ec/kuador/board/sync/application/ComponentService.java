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
import ec.kuador.board.billing.BillingApi;
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

    /** Piezas planeadas antes del repo. {@code layout}: MONOREPO (cada pieza en su carpeta) o MULTI_REPO (un repo por pieza). */
    public record PiecePlan(String layout, List<PieceSpec> pieces) {}

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
    private final BillingApi billing;
    private final PiecePlans plans;
    private final StackDocs stack;

    ComponentService(ComponentRepository components, SyncRecordRepository records, RepositoryCreator creator, TemplateFiles templates,
            CatalogApi catalog, DocsApi docs, ProjectApi projects, RepositoryInstructions agents, Clock clock, BillingApi billing, PiecePlans plans,
            StackDocs stack) {
        this.billing = billing;
        this.plans = plans;
        this.stack = stack;
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

    private org.springframework.beans.factory.ObjectProvider<AgentKitService> kits;

    @org.springframework.beans.factory.annotation.Autowired
    void setKits(org.springframework.beans.factory.ObjectProvider<AgentKitService> kits) {
        this.kits = kits;
    }

    private static final java.util.regex.Pattern REPO = java.util.regex.Pattern.compile("^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$");
    private static final java.util.regex.Pattern PIECE_NAME = java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");

    /**
     * Las piezas de un proyecto que ya existía: las descubrió el reconocimiento y las aprobó una persona. No crea ni toca
     * repos; si ya hay una pieza con ese nombre, se actualiza (el equipo pudo corregir el stack o los comandos).
     */
    @Override
    @Transactional
    public void registerExisting(String projectKey, List<ExistingPiece> pieces, UUID actorId) {
        software(projectKey);
        RepositoryLink link = records.findLink(projectKey)
                .orElseThrow(() -> new DomainException("Primero vincula el repositorio del proyecto."));
        Long installation = KIND.equals(link.kind()) ? Long.valueOf(link.secret()) : null;
        Map<String, ProjectComponent> current = new java.util.HashMap<>();
        components.findByProject(projectKey).forEach(c -> current.put(c.name(), c));
        Instant now = clock.instant();
        for (ExistingPiece p : pieces) {
            String name = p.name() == null ? "" : p.name().strip().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("^-+|-+$", "");
            if (!PIECE_NAME.matcher(name).matches()) throw new DomainException("Nombre de pieza no válido: «" + p.name() + "» (minúsculas, números y guiones).");
            String stack = p.stack() == null ? "" : p.stack().strip();
            if (stack.isEmpty() || stack.length() > 120) throw new DomainException("Indica el stack de «" + name + "» (hasta 120 caracteres).");
            String repo = p.repository() == null || p.repository().isBlank() ? link.location() : p.repository().strip();
            if (!REPO.matcher(repo).matches()) throw new DomainException("El repo de «" + name + "» va como dueño/nombre: " + repo + ".");
            String asked = p.kind() == null ? "" : p.kind().strip().toUpperCase(java.util.Locale.ROOT);
            String kind = java.util.Arrays.stream(ComponentTemplate.Kind.values()).map(Enum::name).anyMatch(asked::equals) ? asked : ComponentTemplate.Kind.OTHER.name();
            String path = p.path() == null ? "" : p.path().strip().replaceAll("^/+|/+$", "");
            String notes = blankToNull(p.notes());
            ProjectComponent existing = current.get(name);
            ProjectComponent c = new ProjectComponent(existing == null ? UUID.randomUUID() : existing.id(), projectKey, name, null, null, null, null, kind,
                    stack, command(p.build()), command(p.test()), command(p.run()), repo, path, installation, actorId,
                    existing == null ? now : existing.createdAt(), notes == null ? null : notes.length() > 4000 ? notes.substring(0, 4000) : notes);
            components.save(c);
        }
        this.stack.refresh(projectKey, this.stack.lines(projectKey), actorId, true);
        log.info("{} piezas existentes registradas en {} por {}", pieces.size(), projectKey, actorId);
    }

    @Override
    @Transactional
    public List<String> proposeKit(String projectKey, UUID actorId) {
        return kits.getObject().propose(projectKey, actorId).stream()
                .map(k -> k.pullRequest() == null ? null : k.pullRequest().url()).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Piece> pieces(String projectKey) {
        return components.findByProject(projectKey).stream()
                .map(c -> new Piece(c.name(), c.kind(), c.stack(), c.templateKey(), c.repoLocation(), c.path(), c.buildCommand(), c.testCommand(), c.runCommand(),
                        c.notes()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Piece> stack(String projectKey) {
        return stack.lines(projectKey).stream()
                .map(l -> new Piece(l.name(), l.kind(), l.stack(), null, null, l.where(), l.build(), l.test(), l.run(), null)).toList();
    }

    @Override
    @Transactional
    public void refreshStack(String projectKey, UUID actorId) {
        this.stack.refresh(projectKey, actorId);
    }

    @Transactional(readOnly = true)
    public List<ProjectComponent> list(String projectKey) {
        software(projectKey);
        return components.findByProject(projectKey);
    }

    /** Lo que el equipo ajusta de una pieza ya creada (p. ej. bajar de Java 25 a Java 21). Vacío = sin comando. */
    public record PieceUpdate(String stack, String build, String test, String run, String notes) {}

    /**
     * Ajusta una pieza ya creada: stack, comandos y notas para los agentes. No toca el repo (el código y el README los
     * cambia el equipo); los agentes leen estos datos en el tablero y el kit los usa la próxima vez que se actualiza.
     */
    @Transactional
    public ProjectComponent update(String projectKey, String name, PieceUpdate u, UUID actorId) {
        software(projectKey);
        ProjectComponent current = components.findByProject(projectKey).stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new ec.kuador.board.shared.error.NotFoundException("No existe la pieza «" + name + "» en " + projectKey + "."));
        String stack = u.stack() == null ? "" : u.stack().strip();
        if (stack.isEmpty() || stack.length() > 120) throw new DomainException("Indica el stack de la pieza (hasta 120 caracteres), p. ej. «Java 21 · Spring Boot 3».");
        String notes = blankToNull(u.notes());
        if (notes != null && notes.length() > 4000) throw new DomainException("Las notas admiten hasta 4000 caracteres.");
        ProjectComponent updated = current.adjusted(stack, command(u.build()), command(u.test()), command(u.run()), notes);
        components.save(updated);
        this.stack.refresh(projectKey, actorId);
        log.info("Pieza {} de {} ajustada por {}", name, projectKey, actorId);
        return updated;
    }

    private static String command(String c) {
        String v = blankToNull(c);
        if (v != null && v.length() > 300) throw new DomainException("Cada comando admite hasta 300 caracteres.");
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    // ---- Armado inicial ----

    @Transactional
    public Result setup(String projectKey, Setup s, UUID actorId) {
        billing.requireFactory("Armar el repo con piezas");
        ProjectView project = software(projectKey);
        if (records.findLink(projectKey).isPresent()) {
            throw new ConflictException("El proyecto ya tiene un repositorio. Suma piezas desde «Agregar pieza».");
        }
        requireBaseReady(project);
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
        // El stack de Arquitectura sale de estas piezas y entra así en el primer commit.
        docs.ensureProjectDocuments(projectKey, actorId);
        this.stack.refresh(projectKey, this.stack.planned(specs, specs.stream().anyMatch(p -> p.repoName() != null && !p.repoName().isBlank())), actorId);
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

        List<File> projectFiles = new ArrayList<>(RepositoryBase.files(catalog.agentPolicy().repo(), project.name(), projectKey, s.description(), agentsMd, documents, lines));
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
        plans.delete(projectKey); // desde ahora mandan las piezas reales
        log.info("Proyecto {} armado: {} pieza(s), {} repo(s)", projectKey, saved.size(), results.size());
        return new Result(link, results, saved, documents.size());
    }

    // ---- Sumar una pieza después ----

    @Transactional
    public Result add(String projectKey, PieceSpec spec, boolean privateRepo, UUID actorId) {
        billing.requireFactory("Sumar piezas al repo");
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
        this.stack.refresh(projectKey, actorId);
        return new Result(link, List.of(result), List.of(saved), 0);
    }

    // ---- Piezas planeadas (antes del repo) ----

    /** Las piezas planeadas de un proyecto nuevo, o vacío si no las hay. */
    @Transactional(readOnly = true)
    public java.util.Optional<PiecePlan> plan(String projectKey) {
        software(projectKey);
        return plans.find(projectKey);
    }

    /**
     * Guarda las piezas planeadas, antes de crear el repo: son la fuente del stack con que se redactan los documentos y
     * el paso del repositorio arranca con ellas. Reescribe el stack de Arquitectura si ya existe.
     */
    @Transactional
    public PiecePlan savePlan(String projectKey, PiecePlan plan, UUID actorId) {
        software(projectKey);
        if (records.findLink(projectKey).isPresent()) {
            throw new ConflictException("El proyecto ya tiene repositorio: suma o ajusta las piezas desde «Piezas y repositorio».");
        }
        String layout = "MULTI_REPO".equals(plan.layout()) ? "MULTI_REPO" : "MONOREPO";
        List<PieceSpec> specs = plan.pieces() == null ? List.of() : plan.pieces();
        if (specs.isEmpty()) throw new DomainException("Elige al menos una pieza: de ellas sale el stack del proyecto.");
        Set<String> names = new HashSet<>();
        for (PieceSpec p : specs) {
            validName(p.name());
            if (!names.add(p.name())) throw new DomainException("Hay dos piezas llamadas «" + p.name() + "».");
            if (p.templateKey() != null && !p.templateKey().isBlank() && catalog.componentTemplate(p.templateKey()).isEmpty()) {
                throw new DomainException("No existe la plantilla de pieza " + p.templateKey() + " en el catálogo.");
            }
        }
        PiecePlan clean = new PiecePlan(layout, specs.stream()
                .map(p -> new PieceSpec(p.name(), blankToNull(p.templateKey()), "MULTI_REPO".equals(layout) ? blankToNull(p.repoName()) : null,
                        p.variables() == null ? Map.of() : p.variables()))
                .toList());
        plans.save(projectKey, clean);
        this.stack.refresh(projectKey, actorId);
        return clean;
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
            files.add(new File(readme, RepositoryBase.pieceReadme(catalog.agentPolicy().repo(), project.name(), project.key(), null, draft.line(folder + "/"))));
        }
        files.addAll(code);
        return new Prepared(spec, template, kind, stack, folder, files);
    }

    /** Repo propio de una pieza: bases de pieza y, encima, el código de su plantilla (gana la plantilla). */
    private RepositoryCreator.Created createPieceRepo(ProjectView project, Prepared p, long installationId, boolean privateRepo, String projectRepo, String agentsMd) {
        List<File> files = new ArrayList<>(RepositoryBase.pieceRepoFiles(catalog.agentPolicy().repo(), project.name(), project.key(), agentsMd, projectRepo, p.line(".")));
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

    /**
     * Un proyecto nuevo no arma su repo sin sus bases: los documentos de su metodología redactados (van en el primer
     * commit y guían a los agentes) o, sin metodología, al menos el objetivo del tablero.
     */
    private void requireBaseReady(ProjectView project) {
        if (project.methodology() == null) {
            if (projects.goal(project.key()).filter(g -> !g.isBlank()).isEmpty()) {
                throw new DomainException("Antes de crear el repo, escribe el objetivo del tablero (Documentos → Redactar con IA): los agentes lo leen con cada tarjeta.");
            }
            return;
        }
        List<String> pending = docs.pendingBaseDocuments(project.key());
        if (!pending.isEmpty()) {
            throw new DomainException("Antes de crear el repo, redacta y aprueba los documentos base de la metodología: " + String.join(", ", pending)
                    + ". Van en el primer commit y guían a los agentes (Documentos → Redactar con IA).");
        }
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
                repository, p.folder(), installationId, actorId, now, null);
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
