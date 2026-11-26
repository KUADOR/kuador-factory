package ec.kuador.board.sync.application;

import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.ComponentTemplate;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.sync.RepositoryInstructions;
import ec.kuador.board.sync.domain.ComponentRepository;
import ec.kuador.board.sync.domain.ProjectComponent;
import ec.kuador.board.sync.domain.RepositoryCreator;
import ec.kuador.board.sync.domain.RepositoryCreator.File;
import ec.kuador.board.sync.domain.RepositoryCreator.Proposal;
import ec.kuador.board.sync.domain.RepositoryLink;
import ec.kuador.board.sync.domain.SyncRecordRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El kit de agentes que vive en cada repo del proyecto (servidor MCP, comando, subagentes por rol, Codex).
 * Compara lo que hay en la rama de trabajo con lo que el tablero generaría hoy y, si cambió (p. ej. un rol
 * nuevo o instrucciones editadas), lo propone en un PR. Nunca escribe directo: lo fusiona una persona.
 * Solo agrega o actualiza archivos; no borra los que el equipo sumó.
 */
@Service
public class AgentKitService {

    private static final Logger log = LoggerFactory.getLogger(AgentKitService.class);
    static final String BRANCH_PREFIX = "tablero/kit-agentes";
    private static final DateTimeFormatter BRANCH_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public enum FileState {
        CURRENT,
        OUTDATED,
        MISSING
    }

    public record KitFile(String path, FileState state) {}

    /** El kit en un repo: cada archivo y, si hay, el PR del tablero que lo actualiza. */
    public record RepoKit(String repository, List<KitFile> files, Proposal pullRequest) {

        public boolean upToDate() {
            return files.stream().allMatch(f -> f.state() == FileState.CURRENT);
        }
    }

    private final SyncRecordRepository records;
    private final ComponentRepository components;
    private final RepositoryCreator github;
    private final RepositoryInstructions instructions;
    private final CatalogApi catalog;
    private final ProjectApi projects;

    AgentKitService(SyncRecordRepository records, ComponentRepository components, RepositoryCreator github, RepositoryInstructions instructions,
            CatalogApi catalog, ProjectApi projects) {
        this.records = records;
        this.components = components;
        this.github = github;
        this.instructions = instructions;
        this.catalog = catalog;
        this.projects = projects;
    }

    /** Estado del kit en cada repo del proyecto. Vacío si el repo no se conectó con la app de GitHub. */
    @Transactional(readOnly = true)
    public List<RepoKit> status(String projectKey) {
        return targets(projectKey).stream().map(t -> {
            Map<String, String> current = github.read(t.installationId(), t.repository(), t.branch(), t.files().stream().map(File::path).toList());
            List<KitFile> files = t.files().stream().map(f -> new KitFile(f.path(), state(f, current))).toList();
            Proposal pr = github.openProposal(t.installationId(), t.repository(), t.branch(), BRANCH_PREFIX).orElse(null);
            return new RepoKit(t.repository(), files, pr);
        }).toList();
    }

    /**
     * Propone el kit vigente en cada repo donde cambió: un PR nuevo, o un commit más en el PR del tablero que
     * siga abierto. Devuelve el estado después de proponer (la rama de trabajo cambia recién al fusionar el PR).
     */
    @Transactional(readOnly = true)
    public List<RepoKit> propose(String projectKey, UUID actorId) {
        int proposed = 0;
        for (Target t : targets(projectKey)) {
            Optional<Proposal> open = github.openProposal(t.installationId(), t.repository(), t.branch(), BRANCH_PREFIX);
            // Con un PR del tablero abierto, se compara con su rama: lo nuevo va como un commit más en ese PR.
            String compareWith = open.map(Proposal::branch).orElse(t.branch());
            Map<String, String> current = github.read(t.installationId(), t.repository(), compareWith, t.files().stream().map(File::path).toList());
            List<File> changed = t.files().stream().filter(f -> state(f, current) != FileState.CURRENT).toList();
            if (!changed.isEmpty()) {
                String title = "chore(agentes): actualiza el kit de agentes del tablero";
                if (open.isPresent()) {
                    github.addCommit(t.installationId(), t.repository(), open.get().branch(), changed, title);
                } else {
                    String branch = BRANCH_PREFIX + "-" + LocalDateTime.now(ZoneId.of("America/Guayaquil")).format(BRANCH_TIME);
                    github.propose(t.installationId(), t.repository(), t.branch(), branch, changed, title, description(changed));
                }
                proposed++;
                log.info("Kit de agentes propuesto en {} ({} archivo(s)) por {}", t.repository(), changed.size(), actorId);
            }
        }
        if (proposed == 0) throw new DomainException("El kit de agentes ya está al día en todos los repos del proyecto.");
        return status(projectKey);
    }

    // ---- Apoyo ----

    /** Un repo del proyecto y el kit que le corresponde. */
    private record Target(long installationId, String repository, String branch, List<File> files) {}

    private List<Target> targets(String projectKey) {
        ProjectView project = projects.find(projectKey).orElseThrow(() -> new NotFoundException("No existe el proyecto " + projectKey + "."));
        if (!project.repository()) throw new DomainException("El kit de agentes es para tableros con repositorio de código.");
        Optional<RepositoryLink> link = records.findLink(projectKey, subject(projectKey)).filter(l -> ComponentService.KIND.equals(l.kind()));
        if (link.isEmpty()) return List.of();
        long installationId = Long.parseLong(link.get().secret());
        List<ProjectComponent> pieces = components.findByProject(projectKey, subject(projectKey));
        Map<String, List<ProjectComponent>> byRepo = new LinkedHashMap<>();
        byRepo.put(link.get().location(), new ArrayList<>());
        pieces.forEach(p -> byRepo.computeIfAbsent(p.repoLocation() == null ? link.get().location() : p.repoLocation(), r -> new ArrayList<>()).add(p));
        List<Target> targets = new ArrayList<>();
        byRepo.forEach((repo, inRepo) -> {
            boolean main = repo.equals(link.get().location());
            // El repo del proyecto lleva los servidores MCP de todas sus piezas; el de una pieza, solo los suyos.
            List<ComponentTemplate> templates = (main ? pieces : inRepo).stream()
                    .map(p -> p.templateKey() == null ? null : catalog.componentTemplate(p.templateKey()).orElse(null)).toList();
            List<File> files = instructions.agentKit(ComponentService.mcpServers(templates)).stream().map(f -> new File(f.path(), f.content())).toList();
            targets.add(new Target(installationId, repo, main ? link.get().branch() : ComponentService.WORK_BRANCH, files));
        });
        return targets;
    }

    private static FileState state(File expected, Map<String, String> current) {
        String found = current.get(expected.path());
        if (found == null) return FileState.MISSING;
        return found.strip().equals(expected.content().strip()) ? FileState.CURRENT : FileState.OUTDATED;
    }

    private static String description(List<File> files) {
        StringBuilder sb = new StringBuilder("""
                El tablero actualiza el kit de agentes de este repo: la conexión con el servidor MCP, el comando `/pendientes`, \
                los subagentes de cada rol y la configuración de Codex. Cambió algo en el tablero (roles, herramientas o reglas).

                | Archivo |
                |---|
                """);
        files.forEach(f -> sb.append("| `").append(f.path()).append("` |\n"));
        return sb.append("\nNo lleva secretos: cada persona inicia sesión en el tablero desde su cliente de IA.").toString();
    }

    /** El proyecto del tablero sobre el que opera la petición (el primero, si atiende uno solo o no se eligió). */
    private String subject(String projectKey) {
        return projects.currentSubject(projectKey);
    }
}
