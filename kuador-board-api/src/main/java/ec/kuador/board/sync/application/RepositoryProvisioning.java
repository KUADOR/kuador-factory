package ec.kuador.board.sync.application;

import ec.kuador.board.docs.DocsApi;
import ec.kuador.board.docs.DocsApi.DocContent;
import ec.kuador.board.mcp.AgentInstructionsApi;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.sync.domain.RepositoryCreator;
import ec.kuador.board.sync.domain.RepositoryLink;
import ec.kuador.board.sync.domain.SyncRecord;
import ec.kuador.board.sync.domain.SyncRecordRepository;
import ec.kuador.board.sync.domain.SyncState;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proyecto nuevo con sus bases en GitHub: crea el repo en una organización conectada, sube las bases
 * (metodología, AGENTS.md, CI, ramas protegidas) y lo deja vinculado y sincronizado con el tablero.
 */
@Service
public class RepositoryProvisioning {

    private static final Logger log = LoggerFactory.getLogger(RepositoryProvisioning.class);
    private static final String KIND = "GITHUB_APP";

    /** El repo creado y lo que GitHub no permitió (p. ej. proteger ramas privadas en el plan gratuito). */
    public record Result(RepositoryLink link, String url, List<String> protectedBranches, List<String> warnings, int documents) {}

    private final SyncRecordRepository records;
    private final RepositoryCreator creator;
    private final DocsApi docs;
    private final ProjectApi projects;
    private final AgentInstructionsApi agents;
    private final Clock clock;

    RepositoryProvisioning(SyncRecordRepository records, RepositoryCreator creator, DocsApi docs, ProjectApi projects, AgentInstructionsApi agents,
            Clock clock) {
        this.records = records;
        this.creator = creator;
        this.docs = docs;
        this.projects = projects;
        this.agents = agents;
        this.clock = clock;
    }

    @Transactional
    public Result create(String projectKey, long installationId, String name, String description, boolean privateRepo, UUID actorId) {
        ProjectView project = projects.find(projectKey).orElseThrow(() -> new NotFoundException("No existe el proyecto " + projectKey + "."));
        if (!project.software()) throw new DomainException("Los repositorios son para proyectos de software.");
        if (records.findLink(projectKey).isPresent()) {
            throw new ConflictException("El proyecto ya tiene un repositorio vinculado. Desvincúlalo antes de crear otro.");
        }
        String agentsMd = agents.agentsMd();
        List<DocContent> documents = docs.ensureProjectDocuments(projectKey, actorId).stream()
                .map(d -> d.path().equals(RepositoryBase.AGENTS_MD) && !d.content().contains(agentsMd.lines().findFirst().orElse(""))
                        ? docs.write(projectKey, null, d.kind(), RepositoryBase.withBoardSection(d.content(), agentsMd), "Agrega cómo trabajar con el tablero", actorId)
                        : d)
                .toList();
        RepositoryCreator.Created created = creator.create(
                new RepositoryCreator.NewRepository(installationId, name, description, privateRepo),
                RepositoryBase.files(project.name(), projectKey, description, agentsMd, documents),
                "chore: bases del proyecto " + projectKey + " desde el tablero");
        Instant now = clock.instant();
        RepositoryLink link = new RepositoryLink(projectKey, KIND, created.fullName(), created.workBranch(), String.valueOf(installationId), now, actorId);
        records.saveLink(link);
        // Los documentos subieron tal cual: quedan sincronizados en su versión actual.
        documents.forEach(d -> records.saveRecord(new SyncRecord(d.id(), d.version(), SyncState.sha256(d.content()), now)));
        log.info("Proyecto {} con repo nuevo {} ({} documentos, ramas protegidas: {})", projectKey, created.fullName(), documents.size(),
                created.protectedBranches());
        return new Result(link, created.htmlUrl(), created.protectedBranches(), created.warnings(), documents.size());
    }
}
