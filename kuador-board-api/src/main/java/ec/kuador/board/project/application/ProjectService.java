package ec.kuador.board.project.application;

import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.CatalogUsage;
import ec.kuador.board.catalog.ColumnDef;
import ec.kuador.board.catalog.StageDef;
import ec.kuador.board.catalog.WorkflowTemplate;
import ec.kuador.board.project.ColumnUsage;
import ec.kuador.board.project.ProjectDeleted;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectView;
import ec.kuador.board.project.Transitions;
import ec.kuador.board.project.domain.Project;
import ec.kuador.board.project.domain.ProjectRepository;
import ec.kuador.board.project.domain.Workflow;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ec.kuador.board.billing.BillingApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService implements ProjectApi, CatalogUsage {

    private final ProjectRepository projects;
    private final ColumnUsage usage;
    private final CatalogApi catalog;
    private final Clock clock;
    private final BillingApi billing;
    private final org.springframework.context.ApplicationEventPublisher events;

    ProjectService(ProjectRepository projects, ColumnUsage usage, CatalogApi catalog, Clock clock, BillingApi billing,
            org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.projects = projects;
        this.usage = usage;
        this.catalog = catalog;
        this.clock = clock;
        this.billing = billing;
    }

    /** Plantillas y metodologías que son parte de la fábrica de software (planes que la incluyen). */
    static final String FACTORY_TEMPLATE = "SOFTWARE_FACTORY";
    static final String FREE_METHODOLOGY = "FREE";

    public record ProjectWithColumns(Project project, List<ColumnDef> columns) {}

    @Transactional(readOnly = true)
    public List<ProjectWithColumns> list() {
        return projects.findAll().stream().map(p -> new ProjectWithColumns(p, projects.findColumns(p.key()))).toList();
    }

    @Transactional(readOnly = true)
    public ProjectWithColumns get(String key) {
        return new ProjectWithColumns(load(key), projects.findColumns(key));
    }

    @Override
    @Transactional
    public void createBoard(String key, String name, String templateKey) {
        create(key, name, null, templateKey);
    }

    /** Crea el proyecto copiando la plantilla de flujo del catálogo del espacio. */
    @Transactional
    public ProjectWithColumns create(String key, String name, String description, String templateKey) {
        WorkflowTemplate template = catalog.workflowTemplate(templateKey)
                .orElseThrow(() -> new NotFoundException("No existe la plantilla de flujo " + templateKey + "."));
        billing.requireRoom(BillingApi.Limit.PROJECTS, projects.findAll().size());
        if (FACTORY_TEMPLATE.equals(template.key())) billing.requireFactory("La plantilla «" + template.name() + "»");
        // Del catálogo se copian columnas, tipos, etapas (roles y puertas) y pasos del flujo.
        Transitions transitions = template.transitions().isEmpty() ? Transitions.FREE
                : new Transitions(true, template.transitions().stream().map(t -> new Transitions.Transition(t.from(), t.to())).toList());
        Project project = Project.create(key, name, description, template.kind(), template.key(), template.issueTypes(), transitions,
                template.stages(), clock.instant());
        if (projects.existsByKey(project.key())) {
            throw new ConflictException("Ya existe un proyecto con la clave " + project.key() + ".");
        }
        Project saved = projects.save(project);
        projects.saveColumns(saved.key(), template.columns());
        events.publishEvent(new ec.kuador.board.project.ProjectCreated(UUID.randomUUID(), TenantContext.require(), saved.key(), template.teamRoles(),
                clock.instant()));
        return new ProjectWithColumns(saved, template.columns());
    }

    @Transactional
    public ProjectWithColumns updateColumns(String key, List<Workflow.Draft> drafts) {
        Project project = load(key);
        List<ColumnDef> columns = Workflow.replace(projects.findColumns(key), drafts, column -> usage.countIssues(key, column));
        projects.saveColumns(key, columns);
        // Las reglas de columnas que desaparecen se descartan; una columna nueva queda sin entrada
        // hasta que se le agreguen reglas (si el flujo está restringido, se avisa en Configuración).
        Transitions pruned = project.transitions().prunedTo(columns);
        if (!pruned.equals(project.transitions())) projects.updateTransitions(key, pruned);
        List<StageDef> stages = StageDef.prunedTo(project.stages(), columns);
        if (!stages.equals(project.stages())) projects.updateStages(key, stages, project.maxRework());
        return new ProjectWithColumns(project.withTransitions(pruned).withStages(stages, project.maxRework()), columns);
    }

    /** Nombre y descripción (solo personas). */
    @Transactional
    public ProjectWithColumns updateDetails(String key, String name, String description) {
        Project updated = load(key).withDetails(name, description); // valida
        projects.updateDetails(key, updated.name(), updated.description());
        return new ProjectWithColumns(updated, projects.findColumns(key));
    }

    /** Etapas: roles responsables, puertas y freno de bucles (solo personas). */
    @Transactional
    public ProjectWithColumns setStages(String key, List<StageDef> stages, int maxRework) {
        Project project = load(key);
        List<ColumnDef> columns = projects.findColumns(key);
        Project updated = project.withStages(StageDef.validate(stages, columns), maxRework); // valida el límite
        projects.updateStages(key, updated.stages(), updated.maxRework());
        return new ProjectWithColumns(updated, columns);
    }

    /** Reglas de transición del flujo (solo personas). */
    @Transactional
    public ProjectWithColumns setTransitions(String key, Transitions transitions) {
        Project project = load(key);
        List<ColumnDef> columns = projects.findColumns(key);
        Transitions valid = transitions.validatedFor(columns);
        projects.updateTransitions(key, valid);
        return new ProjectWithColumns(project.withTransitions(valid), columns);
    }

    /**
     * Borra el tablero con todo su contenido. {@code confirmation} debe ser la clave del tablero: evita borrar uno
     * por error. Los repositorios en GitHub no se tocan.
     */
    @Transactional
    public void delete(String key, String confirmation, UUID actorId) {
        Project project = load(key);
        if (confirmation == null || !confirmation.strip().equalsIgnoreCase(project.key())) {
            throw new DomainException("Para borrar el tablero escribe su clave, " + project.key() + ".");
        }
        projects.delete(project.key());
        events.publishEvent(new ProjectDeleted(UUID.randomUUID(), TenantContext.require(), project.key(), project.name(), actorId, clock.instant()));
    }

    @Transactional
    public ProjectWithColumns setMethodology(String key, String methodologyKey) {
        if (methodologyKey != null && catalog.methodology(methodologyKey).isEmpty()) {
            throw new NotFoundException("No existe la metodología " + methodologyKey + " en el catálogo.");
        }
        if (methodologyKey != null && !FREE_METHODOLOGY.equals(methodologyKey)) billing.requireFactory("Trabajar con una metodología de agentes");
        Project updated = load(key).withMethodology(methodologyKey); // valida que sea de software
        projects.updateMethodology(key, methodologyKey);
        return new ProjectWithColumns(updated, projects.findColumns(key));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(String projectKey) {
        return projects.existsByKey(projectKey);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProjectView> find(String projectKey) {
        return projects.findByKey(projectKey).map(p -> new ProjectView(p.key(), p.name(), p.kind(), p.templateKey(), p.methodology(),
                projects.findColumns(p.key()), p.issueTypes(), p.transitions(), p.stages(), p.maxRework()));
    }

    @Override
    @Transactional(readOnly = true)
    public String name(String projectKey) {
        return projects.findByKey(projectKey).map(Project::name).orElse(projectKey);
    }

    @Override
    @Transactional
    public int allocateIssueNumber(String projectKey) {
        return projects.nextIssueNumber(projectKey)
                .orElseThrow(() -> new NotFoundException("No existe el proyecto " + projectKey + "."));
    }

    // ---- CatalogUsage: el catálogo no deja borrar una metodología en uso ----

    @Override
    @Transactional(readOnly = true)
    public long projectsUsingMethodology(String methodologyKey) {
        return projects.countUsingMethodology(methodologyKey);
    }

    @Override
    public long membersWithRole(String roleKey) {
        return 0;
    }

    private Project load(String key) {
        return projects.findByKey(key).orElseThrow(() -> new NotFoundException("No existe el proyecto " + key + "."));
    }
}
