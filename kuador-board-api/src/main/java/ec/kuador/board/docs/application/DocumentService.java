package ec.kuador.board.docs.application;

import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.DocumentDef;
import ec.kuador.board.catalog.Methodology;
import ec.kuador.board.docs.DocsApi;
import ec.kuador.board.docs.DocumentSaved;
import ec.kuador.board.docs.domain.Document;
import ec.kuador.board.docs.domain.DocumentPaths;
import ec.kuador.board.docs.domain.DocumentRepository;
import ec.kuador.board.docs.domain.DocumentTemplate;
import ec.kuador.board.docs.domain.DocumentVersion;
import ec.kuador.board.issue.IssueApi;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de documentos. Qué documentos existen, dónde viven en el repo y con qué contenido
 * nacen lo define la metodología del proyecto en el catálogo del espacio. Personas y agentes pueden
 * escribir.
 */
@Service
public class DocumentService implements DocsApi {

    public static final int MAX_CONTENT = 200_000;
    private static final ZoneId ECUADOR = ZoneId.of("America/Guayaquil");
    private static final String FROM_TEMPLATE = "Creado desde la plantilla";

    private final DocumentRepository documents;
    private final ProjectApi projects;
    private final IssueApi issues;
    private final CatalogApi catalog;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    DocumentService(DocumentRepository documents, ProjectApi projects, IssueApi issues, CatalogApi catalog, ApplicationEventPublisher events,
            Clock clock) {
        this.documents = documents;
        this.projects = projects;
        this.issues = issues;
        this.catalog = catalog;
        this.events = events;
        this.clock = clock;
    }

    /** {@code methodology} nula si el proyecto aún no eligió una. */
    public record Overview(ProjectView project, Methodology methodology, List<Document> documents) {}

    public record WithContent(Document document, String content) {}

    @Transactional(readOnly = true)
    public Overview overview(String projectKey) {
        ProjectView project = documentsBoard(projectKey);
        return new Overview(project, catalog.methodology(project.methodology()).orElse(null), documents.findByProject(projectKey));
    }

    @Transactional(readOnly = true)
    public List<Document> ofIssue(String issueKey) {
        IssueApi.IssueRef issue = issues.find(issueKey).orElseThrow(() -> new NotFoundException("No existe el issue " + issueKey + "."));
        documentsBoard(issue.projectKey());
        return documents.findByIssue(issueKey);
    }

    /** Crea el documento con la plantilla de su metodología como versión 1. */
    @Transactional
    public WithContent create(String projectKey, String kind, String issueKey, UUID actorId) {
        return createDocument(projectKey, kind, issueKey, null, null, actorId);
    }

    /**
     * Crea un documento. Sin {@code importedPath}, calcula la ruta con el patrón de la metodología;
     * con él, conserva la ruta real del repositorio. Sin {@code content}, usa la plantilla.
     */
    private WithContent createDocument(String projectKey, String kind, String issueKey, String importedPath, String content, UUID actorId) {
        ProjectView project = documentsBoard(projectKey);
        DocumentDef def;
        if (Methodology.DEPLOYMENT_KIND.equals(kind) && project.methodology() == null) {
            def = Methodology.DEPLOYMENT; // Despliegue no depende de la metodología
        } else {
            Methodology methodology = methodologyOf(project);
            def = methodology.document(kind)
                    .orElseThrow(() -> new DomainException("La metodología " + methodology.name() + " no usa documentos de tipo " + kind + "."));
        }
        if (content != null && content.length() > MAX_CONTENT) {
            throw new DomainException("El documento pasa de " + MAX_CONTENT + " caracteres.");
        }
        IssueApi.IssueRef issue = null;
        String path;
        if (def.scope() == DocumentDef.Scope.ISSUE) {
            if (issueKey == null || issueKey.isBlank()) {
                throw new DomainException("«" + def.label() + "» es un documento de issue: indica el issue.");
            }
            issue = issues.find(issueKey).filter(i -> i.projectKey().equals(projectKey))
                    .orElseThrow(() -> new NotFoundException("No existe el issue " + issueKey + " en " + projectKey + "."));
            path = importedPath != null ? importedPath : DocumentPaths.forIssue(def.pathPattern(), issue.key(), issue.number(), issue.title());
        } else {
            if (issueKey != null && !issueKey.isBlank()) {
                throw new DomainException("«" + def.label() + "» es un documento del proyecto, no de un issue.");
            }
            path = importedPath != null ? importedPath : def.pathPattern();
        }
        String scopeKey = issue == null ? null : issue.key();
        if (documents.exists(projectKey, scopeKey, kind)) {
            throw new ConflictException("Ya existe «" + def.label() + "»" + (issue == null ? " en el proyecto." : " para " + scopeKey + "."));
        }
        if (documents.findByProject(projectKey).stream().anyMatch(d -> d.path().equals(path))) {
            throw new ConflictException("Ya hay un documento con la ruta " + path + ".");
        }
        Instant now = clock.instant();
        String title = issue == null ? def.label() : def.label() + " · " + issue.title();
        if (title.length() > 200) title = title.substring(0, 200);
        Document doc = documents.save(new Document(UUID.randomUUID(), projectKey, scopeKey, kind, title, path, 1, now, now, actorId));
        String body = content != null ? content
                : DocumentTemplate.render(def.template(), project.name(), scopeKey, issue == null ? null : issue.title(), LocalDate.ofInstant(now, ECUADOR));
        String note = content == null ? FROM_TEMPLATE : importedPath != null ? "Importado de " + path : "Creado por un agente";
        documents.saveVersion(new DocumentVersion(doc.id(), 1, body, actorId, note, now));
        publish(doc, actorId, now);
        return new WithContent(doc, body);
    }

    @Transactional(readOnly = true)
    public WithContent get(UUID id) {
        Document doc = load(id);
        return new WithContent(doc, content(doc.id(), doc.currentVersion()));
    }

    /**
     * Guarda una versión nueva. {@code baseVersion} es la versión sobre la que se editó: si otra
     * persona o agente guardó antes, se rechaza para no pisar su trabajo. Si el contenido no cambió,
     * no crea versión.
     */
    @Transactional
    public WithContent save(UUID id, int baseVersion, String content, String note, UUID actorId) {
        Document doc = load(id);
        if (baseVersion != doc.currentVersion()) {
            throw new ConflictException("Este documento cambió mientras lo editabas: ya va en la versión " + doc.currentVersion()
                    + " y tú editaste la " + baseVersion + ". Copia tus cambios, recarga y vuelve a aplicarlos.");
        }
        if (content == null || content.length() > MAX_CONTENT) {
            throw new DomainException("El documento no puede pasar de " + MAX_CONTENT + " caracteres.");
        }
        if (content.equals(content(doc.id(), doc.currentVersion()))) {
            return new WithContent(doc, content);
        }
        Instant now = clock.instant();
        Document updated = documents.save(doc.withNewVersion(doc.currentVersion() + 1, actorId, now));
        documents.saveVersion(new DocumentVersion(doc.id(), updated.currentVersion(), content, actorId, clean(note), now));
        publish(updated, actorId, now);
        return new WithContent(updated, content);
    }

    @Transactional(readOnly = true)
    public List<DocumentVersion> versions(UUID id) {
        load(id);
        return documents.findVersions(id);
    }

    @Transactional(readOnly = true)
    public DocumentVersion version(UUID id, int version) {
        load(id);
        return documents.findVersion(id, version)
                .orElseThrow(() -> new NotFoundException("El documento no tiene versión " + version + "."));
    }

    /** Restaurar no borra historia: crea una versión nueva con el contenido de la antigua. */
    @Transactional
    public WithContent restore(UUID id, int version, int baseVersion, UUID actorId) {
        return save(id, baseVersion, version(id, version).content(), "Restaurada desde la versión " + version, actorId);
    }

    // ---- DocsApi ----

    @Override
    @Transactional(readOnly = true)
    public List<DocContent> ofProject(String projectKey) {
        return documents.findByProject(projectKey).stream().map(d -> view(d, content(d.id(), d.currentVersion()))).toList();
    }

    @Override
    @Transactional
    public DocContent saveFromRepository(UUID documentId, int baseVersion, String content, String note, UUID actorId) {
        WithContent saved = save(documentId, baseVersion, content, note, actorId);
        return view(saved.document(), saved.content());
    }

    @Override
    @Transactional
    public DocContent createFromRepository(String projectKey, String kind, String issueKey, String path, String content, UUID actorId) {
        WithContent created = createDocument(projectKey, kind, issueKey, path, content, actorId);
        return view(created.document(), created.content());
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> allowedKinds(String projectKey) {
        // Despliegue lo trae el tablero aunque la metodología no lo tenga (o no haya metodología).
        return catalog.methodology(documentsBoard(projectKey).methodology())
                .map(m -> java.util.stream.Stream.concat(m.documents(DocumentDef.Scope.PROJECT).stream(), m.documents(DocumentDef.Scope.ISSUE).stream())
                        .map(DocumentDef::kind).toList())
                .orElse(List.of(Methodology.DEPLOYMENT_KIND));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MethodologyRules> methodologyRules(String projectKey) {
        return catalog.methodology(documentsBoard(projectKey).methodology())
                .map(m -> new MethodologyRules(m.key(), m.name(), m.summary(), m.agentRules()));
    }

    @Override
    @Transactional
    public List<DocContent> ensureProjectDocuments(String projectKey, UUID actorId) {
        ProjectView project = documentsBoard(projectKey);
        catalog.methodology(project.methodology()).ifPresent(m -> {
            List<Document> existing = documents.findByProject(projectKey);
            for (DocumentDef def : m.documents(DocumentDef.Scope.PROJECT)) {
                boolean has = existing.stream().anyMatch(d -> d.issueKey() == null && d.kind().equals(def.kind()));
                if (!has) create(projectKey, def.kind(), null, actorId);
            }
        });
        return ofProject(projectKey).stream().filter(d -> d.issueKey() == null).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> pendingBaseDocuments(String projectKey) {
        ProjectView project = documentsBoard(projectKey);
        return catalog.methodology(project.methodology()).map(m -> {
            List<Document> existing = documents.findByProject(projectKey).stream().filter(d -> d.issueKey() == null).toList();
            return m.baseDocuments().stream()
                    .filter(def -> existing.stream().filter(d -> d.kind().equals(def.kind())).findFirst().map(this::onlyTemplate).orElse(true))
                    .map(DocumentDef::label).toList();
        }).orElse(List.of());
    }

    /** Sigue como salió de la plantilla: nadie lo redactó, editó ni importó (el stack que escribe el tablero no cuenta). */
    private boolean onlyTemplate(Document d) {
        return documents.findVersions(d.id()).stream().allMatch(v -> FROM_TEMPLATE.equals(v.note()) || STACK_NOTE.equals(v.note()));
    }

    @Override
    @Transactional
    public DocContent write(String projectKey, String issueKey, String kind, String content, String note, UUID actorId) {
        Optional<Document> existing = (issueKey == null
                ? documents.findByProject(projectKey).stream().filter(d -> d.issueKey() == null)
                : documents.findByIssue(issueKey).stream()).filter(d -> d.kind().equals(kind)).findFirst();
        if (existing.isPresent()) {
            WithContent saved = save(existing.get().id(), existing.get().currentVersion(), content, note, actorId);
            return view(saved.document(), saved.content());
        }
        WithContent created = createDocument(projectKey, kind, issueKey, null, content, actorId);
        return view(created.document(), created.content());
    }

    // ---- Apoyo ----

    private static DocContent view(Document d, String content) {
        return new DocContent(d.id(), d.projectKey(), d.issueKey(), d.kind(), d.title(), d.path(), d.currentVersion(), content);
    }

    private void publish(Document doc, UUID actorId, Instant now) {
        events.publishEvent(new DocumentSaved(UUID.randomUUID(), TenantContext.require(), doc.id(), doc.projectKey(), doc.issueKey(),
                doc.kind(), doc.title(), doc.path(), doc.currentVersion(), actorId, now));
    }

    private Methodology methodologyOf(ProjectView project) {
        if (project.methodology() == null) {
            throw new DomainException("Elige primero la metodología del proyecto en Configurar.");
        }
        return catalog.methodology(project.methodology()).orElseThrow(() -> new DomainException(
                "La metodología " + project.methodology() + " ya no existe en el catálogo del espacio. Elige otra en Configurar."));
    }

    private ProjectView documentsBoard(String projectKey) {
        ProjectView project = projects.find(projectKey)
                .orElseThrow(() -> new NotFoundException("No existe el proyecto " + projectKey + "."));
        if (!project.documents()) {
            throw new DomainException("Este tablero no usa documentos.");
        }
        return project;
    }

    private String content(UUID id, int version) {
        return documents.findVersion(id, version).map(DocumentVersion::content).orElse("");
    }

    private Document load(UUID id) {
        return documents.findById(id).orElseThrow(() -> new NotFoundException("No existe el documento " + id + "."));
    }

    private static String clean(String note) {
        if (note == null || note.isBlank()) return null;
        String n = note.strip();
        return n.length() > 200 ? n.substring(0, 200) : n;
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<String> projectOf(UUID documentId) {
        return documents.findById(documentId).map(ec.kuador.board.docs.domain.Document::projectKey);
    }
}
