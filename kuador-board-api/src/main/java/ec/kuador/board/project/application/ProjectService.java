package ec.kuador.board.project.application;

import ec.kuador.board.catalog.Capability;
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
import ec.kuador.board.member.MemberAccess;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.domain.BoardPeople;
import ec.kuador.board.shared.tenant.BoardScope;
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
    private final BoardPeople people;
    private final MemberApi members;
    private ProjectGoals goals;

    @org.springframework.beans.factory.annotation.Autowired
    void setGoals(ProjectGoals goals) {
        this.goals = goals;
    }

    ProjectService(ProjectRepository projects, ColumnUsage usage, CatalogApi catalog, Clock clock, BillingApi billing,
            org.springframework.context.ApplicationEventPublisher events, BoardPeople people, MemberApi members) {
        this.events = events;
        this.people = people;
        this.members = members;
        this.projects = projects;
        this.usage = usage;
        this.catalog = catalog;
        this.clock = clock;
        this.billing = billing;
    }

    /** El manual sin reglas de agentes: no es parte de la fábrica de software (planes que la incluyen). */
    static final String FREE_METHODOLOGY = "FREE";

    /**
     * Una plantilla es de la fábrica de software si trabaja con repositorio y sus etapas las cubren roles: no depende de
     * su clave, así una copia con otro nombre cuenta igual.
     */
    static boolean softwareFactory(WorkflowTemplate template) {
        return template.can(Capability.REPOSITORY) && template.stages().stream().anyMatch(s -> !s.roles().isEmpty());
    }

    public record ProjectWithColumns(Project project, List<ColumnDef> columns) {}

    /** Los tableros que puede ver quien pide (ver {@link BoardScope}). */
    @Transactional(readOnly = true)
    public List<ProjectWithColumns> list() {
        return projects.findAll().stream().filter(p -> BoardScope.allows(p.key()))
                .map(p -> new ProjectWithColumns(p, projects.findColumns(p.key()))).toList();
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

    /** Crea el tablero a pedido de una persona, que queda en él (si no administra, es la única forma de verlo). */
    @Transactional
    public ProjectWithColumns create(String key, String name, String description, String templateKey, UUID creatorId) {
        ProjectWithColumns created = create(key, name, description, templateKey);
        if (creatorId != null && members.find(creatorId).filter(m -> !m.agent()).isPresent()) people.add(created.project().key(), creatorId);
        return created;
    }

    /** Crea el proyecto copiando la plantilla de flujo del catálogo del espacio. */
    @Transactional
    public ProjectWithColumns create(String key, String name, String description, String templateKey) {
        WorkflowTemplate template = catalog.workflowTemplate(templateKey)
                .orElseThrow(() -> new NotFoundException("No existe la plantilla de flujo " + templateKey + "."));
        billing.requireRoom(BillingApi.Limit.PROJECTS, projects.findAll().size());
        if (softwareFactory(template)) billing.requireFactory("La plantilla «" + template.name() + "»");
        // Del catálogo se copian columnas, tipos, etapas (roles y puertas) y pasos del flujo.
        Transitions transitions = template.transitions().isEmpty() ? Transitions.FREE
                : new Transitions(true, template.transitions().stream().map(t -> new Transitions.Transition(t.from(), t.to())).toList());
        Project project = Project.create(key, name, description, template.capabilities(), template.key(), template.issueTypes(), transitions,
                template.stages(), template.estimate(), clock.instant());
        if (projects.existsByKey(project.key())) {
            throw new ConflictException("Ya existe un proyecto con la clave " + project.key() + ".");
        }
        Project saved = projects.save(project);
        projects.saveColumns(saved.key(), template.columns());
        events.publishEvent(new ec.kuador.board.project.ProjectCreated(UUID.randomUUID(), TenantContext.require(), saved.key(), catalog.defaultTeam(template.key()),
                template.can(ec.kuador.board.catalog.Capability.REPOSITORY), clock.instant()));
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
        List<String> reviewers = updated.stages().stream().filter(s -> s.review() != null).map(StageDef::reviewerRole).distinct().toList();
        if (!reviewers.isEmpty()) {
            events.publishEvent(new ec.kuador.board.project.ReviewersNeeded(UUID.randomUUID(), TenantContext.require(), key, reviewers, clock.instant()));
        }
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

    /**
     * Qué usa el tablero (solo personas). No se apaga lo que ya tiene contenido: los documentos con un manual elegido, ni
     * el repositorio una vez encendido (hay ramas, piezas y PR que dependen de él).
     */
    @Transactional
    public ProjectWithColumns setCapabilities(String key, List<Capability> capabilities) {
        Project project = load(key);
        List<Capability> wanted = Capability.normalize(capabilities == null ? List.of() : capabilities);
        if (project.can(Capability.REPOSITORY) && !wanted.contains(Capability.REPOSITORY)) {
            throw new DomainException("Este tablero ya trabaja con un repositorio de código: eso no se apaga.");
        }
        if (!project.can(Capability.REPOSITORY) && wanted.contains(Capability.REPOSITORY)) {
            throw new DomainException("El repositorio de código se elige al crear el tablero, con una plantilla de software.");
        }
        if (project.methodology() != null && !wanted.contains(Capability.DOCUMENTS)) {
            throw new DomainException("Este tablero tiene un manual de trabajo elegido: quítalo antes de apagar los documentos.");
        }
        Project updated = project.withCapabilities(wanted);
        projects.updateCapabilities(key, updated.capabilities());
        return new ProjectWithColumns(updated, projects.findColumns(key));
    }

    /** La forma de la tarjeta: nombres de los tipos, sus datos propios y la unidad de medida (solo personas). */
    @Transactional
    public ProjectWithColumns setCard(String key, List<ec.kuador.board.catalog.IssueTypeDef> issueTypes, ec.kuador.board.catalog.EstimateUnit estimate) {
        Project updated = load(key).withCard(issueTypes, estimate);
        projects.updateCard(key, updated.issueTypes(), updated.estimate());
        return new ProjectWithColumns(updated, projects.findColumns(key));
    }

    @Transactional
    public ProjectWithColumns setMethodology(String key, String methodologyKey) {
        if (methodologyKey != null && catalog.methodology(methodologyKey).isEmpty()) {
            throw new NotFoundException("No existe la metodología " + methodologyKey + " en el catálogo.");
        }
        Project current = load(key);
        // El manual de un tablero sin repositorio (una clínica, ventas) no es la fábrica de software.
        if (methodologyKey != null && !FREE_METHODOLOGY.equals(methodologyKey) && current.can(Capability.REPOSITORY)) {
            billing.requireFactory("Trabajar con una metodología de agentes");
        }
        Project updated = current.withMethodology(methodologyKey); // valida que el tablero use documentos
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
    public List<String> visibleKeys() {
        return projects.findAll().stream().map(Project::key).filter(BoardScope::allows).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProjectView> find(String projectKey) {
        return projects.findByKey(projectKey).map(p -> new ProjectView(p.key(), p.name(), p.kind(), p.templateKey(), p.methodology(),
                projects.findColumns(p.key()), p.issueTypes(), p.transitions(), p.stages(), p.maxRework(), p.capabilities(), p.estimate()));
    }

    @Override
    @Transactional(readOnly = true)
    public String name(String projectKey) {
        return projects.findByKey(projectKey).map(Project::name).orElse(projectKey);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<String> goal(String projectKey) {
        return goals.find(projectKey);
    }

    @Override
    @Transactional
    public void saveGoal(String projectKey, String goal) {
        setGoal(projectKey, goal);
    }

    @Transactional
    public java.util.Optional<String> setGoal(String projectKey, String goal) {
        if (!exists(projectKey)) throw new ec.kuador.board.shared.error.NotFoundException("No existe el proyecto " + projectKey + ".");
        goals.save(projectKey, goal);
        return goals.find(projectKey);
    }

    // ---- Personas del tablero ----

    /** Una persona del espacio y si está en el tablero; {@code implicit}: lo ve por su nivel (dueña o administradora). */
    public record BoardPerson(UUID id, String name, String role, String avatarColor, MemberAccess access, boolean inBoard, boolean implicit) {}

    @Transactional(readOnly = true)
    public List<BoardPerson> people(String key) {
        String k = load(key).key();
        java.util.Set<UUID> in = new java.util.HashSet<>(people.of(k));
        return members.humans().stream().map(m -> new BoardPerson(m.id(), m.name(), m.role(), m.avatarColor(), m.access(),
                m.administers() || in.contains(m.id()), m.administers())).toList();
    }

    @Transactional
    public List<BoardPerson> addToBoard(String key, UUID memberId) {
        addPerson(key, memberId);
        return people(key);
    }

    @Override
    @Transactional
    public void addPerson(String key, UUID memberId) {
        String k = load(key).key();
        MemberView m = members.find(memberId).filter(x -> !x.agent())
                .orElseThrow(() -> new NotFoundException("No existe esa persona en el equipo."));
        people.add(k, m.id());
    }

    @Transactional
    public List<BoardPerson> removePerson(String key, UUID memberId) {
        String k = load(key).key();
        MemberView m = members.find(memberId).filter(x -> !x.agent())
                .orElseThrow(() -> new NotFoundException("No existe esa persona en el equipo."));
        if (m.administers()) throw new DomainException(m.name() + " administra el espacio: ve todos los tableros por su nivel.");
        people.remove(k, m.id());
        return people(k);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canAccess(UUID memberId, String projectKey) {
        if (memberId == null || projectKey == null) return false;
        return members.find(memberId).map(m -> m.agent() || m.administers() || people.contains(projectKey.toUpperCase(java.util.Locale.ROOT), m.id()))
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Set<String> boardsOf(UUID memberId) {
        return java.util.Set.copyOf(people.boardsOf(memberId));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Set<String> agentBoardsOf(UUID agentId) {
        return java.util.Set.copyOf(people.agentBoardsOf(agentId));
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
