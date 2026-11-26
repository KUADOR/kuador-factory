package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.AgentMode;
import ec.kuador.board.agents.domain.AgentTeamStore;
import ec.kuador.board.catalog.AgentPolicy;
import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.ProjectApi;
import ec.kuador.board.project.ProjectCreated;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.TenantAwareListener;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Los agentes del equipo de cada tablero. Un tablero nuevo suma al equipo los roles de su plantilla que le falten;
 * después el equipo se arma en Organización → Equipos. Cada agente trabaja en la PC de una persona (LOCAL, por MCP) o en el tablero con la
 * clave de IA de la empresa (SERVER).
 */
@Service
public class BoardTeam extends TenantAwareListener {

    private static final String[] COLORS = {"#8fb4ff", "#34d399", "#c4a7ff", "#ff8a4c", "#ff6b78", "#5eead4", "#f9a8d4", "#22d3ee"};

    private final AgentTeamStore store;
    private final MemberApi members;
    private final ProjectApi projects;
    private final CatalogApi catalog;
    private final Clock clock;
    private final AiProviders providers;

    BoardTeam(AgentTeamStore store, MemberApi members, ProjectApi projects, CatalogApi catalog, Clock clock, PlatformTransactionManager txManager,
            AiProviders providers) {
        this.providers = providers;
        super(txManager);
        this.store = store;
        this.members = members;
        this.projects = projects;
        this.catalog = catalog;
        this.clock = clock;
    }

    /**
     * {@code model} y {@code effort}: los elegidos para el agente ({@code null} = los sugeridos); {@code suggestedModel} y
     * {@code suggestedEffort}: los de su rol en Catálogo → Reglas de agentes ({@code null} = los del espacio y del proveedor).
     */
    public record TeamAgent(UUID id, String name, String role, String roleName, String description, String avatarColor, AgentMode mode,
            String model, String effort, String suggestedModel, String suggestedEffort, boolean inTeam, boolean serverAllowed) {}

    /** Con qué modelo y esfuerzo trabaja un agente: lo elegido para él o lo sugerido para su rol ({@code null} = por defecto). */
    public record Choice(String model, String effort) {}

    @Transactional(readOnly = true)
    public Choice choice(UUID memberId) {
        AgentTeamStore.Profile p = store.profile(memberId).orElse(null);
        AgentPolicy.Tuning t = members.find(memberId).map(m -> catalog.agentPolicy().tuning(m.role())).orElse(AgentPolicy.Tuning.NONE);
        String model = p != null && p.model() != null ? p.model() : t.model();
        String effort = p != null && p.effort() != null ? p.effort() : t.effort();
        return new Choice(model, effort != null && AgentPolicy.EFFORTS.contains(effort) ? effort : null);
    }

    /** Agentes del tablero primero; después los del espacio que se pueden sumar. */
    @Transactional(readOnly = true)
    public List<TeamAgent> team(String projectKey) {
        UUID ws = TenantContext.require();
        requireBoard(projectKey);
        List<UUID> team = store.team(ws, projectKey);
        Map<UUID, AgentTeamStore.Profile> profiles = store.profiles(ws);
        AgentPolicy policy = catalog.agentPolicy();
        return members.agents().stream()
                .map(m -> view(m, profiles.get(m.id()), team.contains(m.id()), policy))
                .sorted(java.util.Comparator.comparing((TeamAgent a) -> !a.inTeam()).thenComparing(a -> team.indexOf(a.id())))
                .toList();
    }

    @Transactional
    public List<TeamAgent> add(String projectKey, UUID memberId) {
        requireBoard(projectKey);
        agent(memberId);
        store.addToTeam(TenantContext.require(), projectKey, memberId);
        return team(projectKey);
    }

    @Transactional
    public List<TeamAgent> remove(String projectKey, UUID memberId) {
        requireBoard(projectKey);
        store.removeFromTeam(TenantContext.require(), projectKey, memberId);
        return team(projectKey);
    }

    /** Crea un agente desde el tablero (con un rol del catálogo y su especialidad) y lo suma a su equipo. */
    @Transactional
    public List<TeamAgent> create(String projectKey, String name, String roleKey, String description, AgentMode mode, String model) {
        requireBoard(projectKey);
        MemberView agent = newAgent(name, roleKey, description, mode, model);
        store.addToTeam(TenantContext.require(), projectKey, agent.id());
        return team(projectKey);
    }

    /** Los agentes del espacio y si están en el equipo (Organización → Equipos); los del equipo primero. */
    @Transactional(readOnly = true)
    public List<TeamAgent> teamAgents(UUID teamId) {
        UUID ws = TenantContext.require();
        requireTeam(teamId);
        List<UUID> team = store.teamAgents(ws, teamId);
        Map<UUID, AgentTeamStore.Profile> profiles = store.profiles(ws);
        AgentPolicy policy = catalog.agentPolicy();
        return members.agents().stream()
                .map(m -> view(m, profiles.get(m.id()), team.contains(m.id()), policy))
                .sorted(java.util.Comparator.comparing((TeamAgent a) -> !a.inTeam()).thenComparing(a -> team.indexOf(a.id())))
                .toList();
    }

    /** Crea un agente (con un rol del catálogo y su especialidad) dentro de un equipo. */
    @Transactional
    public List<TeamAgent> createInTeam(UUID teamId, String name, String roleKey, String description, AgentMode mode, String model) {
        requireTeam(teamId);
        UUID ws = TenantContext.require();
        MemberView agent = newAgent(name, roleKey, description, mode, model);
        store.addToTeamById(ws, teamId, agent.id());
        return teamAgents(teamId);
    }

    private MemberView newAgent(String name, String roleKey, String description, AgentMode mode, String model) {
        AgentRole role = catalog.role(roleKey).orElseThrow(() -> new DomainException("Elige un rol del catálogo (o créalo en Catálogo → Roles)."));
        String agentName = name == null || name.isBlank() ? "Agente " + role.name() : name.strip();
        MemberView agent = members.createAgent(role.key(), agentName, description == null || description.isBlank() ? role.description() : description.strip(),
                COLORS[(int) (Math.abs(agentName.hashCode()) % COLORS.length)]);
        store.saveProfile(TenantContext.require(), new AgentTeamStore.Profile(agent.id(), allowedMode(role, mode), cleanModel(model)), clock.instant());
        return agent;
    }

    private void requireTeam(UUID teamId) {
        if (!projects.teamExists(teamId)) throw new NotFoundException("No existe ese equipo.");
    }

    /** Dónde trabaja el agente y con qué modelo ({@code null} = el del espacio). */
    @Transactional
    public TeamAgent setProfile(UUID memberId, AgentMode mode, String model) {
        return setProfile(memberId, mode, model, store.profile(memberId).map(AgentTeamStore.Profile::effort).orElse(null));
    }

    /** Dónde trabaja, con qué modelo y cuánto razona ({@code null} = lo sugerido para su rol). */
    @Transactional
    public TeamAgent setProfile(UUID memberId, AgentMode mode, String model, String effort) {
        MemberView agent = agent(memberId);
        AgentTeamStore.Profile profile = new AgentTeamStore.Profile(memberId, allowedMode(catalog.role(agent.role()).orElse(null), mode), cleanModel(model),
                cleanEffort(effort));
        store.saveProfile(TenantContext.require(), profile, clock.instant());
        return view(agent, profile, false, catalog.agentPolicy());
    }

    private static String cleanEffort(String effort) {
        if (effort == null || effort.isBlank()) return null;
        String e = effort.strip().toLowerCase(java.util.Locale.ROOT);
        if (!AgentPolicy.EFFORTS.contains(e)) throw new DomainException("El esfuerzo es uno de: " + String.join(", ", AgentPolicy.EFFORTS) + ".");
        return e;
    }

    /** Dónde trabaja de verdad: un rol que trabaja sobre el código trabaja local aunque esté guardado «en el tablero». */
    @Transactional(readOnly = true)
    public AgentTeamStore.Profile profile(UUID memberId) {
        AgentTeamStore.Profile p = store.profile(memberId).orElse(new AgentTeamStore.Profile(memberId, AgentMode.LOCAL, null));
        boolean code = members.find(memberId).map(m -> worksOnCode(m.role())).orElse(false);
        return code && p.mode() == AgentMode.SERVER ? new AgentTeamStore.Profile(memberId, AgentMode.LOCAL, p.model(), p.effort()) : p;
    }

    /** El rol escribe el código del repo: no puede trabajar en el tablero. */
    public boolean worksOnCode(String roleKey) {
        return catalog.role(roleKey).map(AgentRole::worksOnCode).orElse(false);
    }

    private static AgentMode allowedMode(AgentRole role, AgentMode mode) {
        if (mode != AgentMode.SERVER) return AgentMode.LOCAL;
        if (role != null && role.worksOnCode()) {
            throw new DomainException("«" + role.name() + "» escribe el código del repo: en el tablero no tiene el repo ni con qué ejecutarlo. "
                    + "Déjalo «En la PC de una persona» y lo trabaja su Claude Code o Codex con /pendientes.");
        }
        return mode;
    }

    /** Tablero nuevo: su equipo es el de la plantilla (los agentes del espacio con esos roles). En otro hilo, tras el commit. */
    @org.springframework.scheduling.annotation.Async
    @TransactionalEventListener
    void on(ProjectCreated event) {
        inTenantTransaction(event, () -> {
            Map<UUID, AgentTeamStore.Profile> profiles = store.profiles(event.workspaceId());
            // Un rol del equipo sin agente en el espacio (p. ej. un rol que llegó con una versión del catálogo): se crea su agente.
            for (String roleKey : event.teamRoles()) {
                if (members.agents().stream().anyMatch(m -> m.role().equals(roleKey))) continue;
                catalog.role(roleKey).ifPresent(role -> {
                    CatalogApi.AgentSeed seed = catalog.agentSeed(roleKey).orElse(new CatalogApi.AgentSeed(roleKey, "Agente " + role.name(), COLORS[0]));
                    members.createAgent(role.key(), seed.memberName(), role.description(), seed.color());
                });
            }
            // Un equipo que ya atiende otros tableros suma solo los roles que le faltan.
            java.util.Set<String> covered = store.team(event.workspaceId(), event.projectKey()).stream().map(members::find)
                    .flatMap(Optional::stream).map(MemberView::role).collect(java.util.stream.Collectors.toSet());
            members.agents().stream().filter(m -> event.teamRoles().contains(m.role()) && !covered.contains(m.role())).forEach(m -> {
                store.addToTeam(event.workspaceId(), event.projectKey(), m.id());
                // Fuera de software nadie tiene un Claude Code abierto: el agente trabaja en el tablero (si nadie eligió otra cosa).
                if (!event.software() && !profiles.containsKey(m.id()) && !worksOnCode(m.role())) {
                    store.saveProfile(event.workspaceId(), new AgentTeamStore.Profile(m.id(), AgentMode.SERVER, null), clock.instant());
                }
            });
        });
    }

    /**
     * Un tablero puso una puerta en revisión por agente: si el espacio no tiene un agente con ese rol, se crea; se suma
     * al equipo del tablero y trabaja en el tablero (revisa los PRs en GitHub, no necesita una PC).
     */
    @TransactionalEventListener(phase = org.springframework.transaction.event.TransactionPhase.BEFORE_COMMIT)
    void on(ec.kuador.board.project.ReviewersNeeded event) {
        for (String roleKey : event.roles()) {
            Optional<AgentRole> role = catalog.role(roleKey);
            if (role.isEmpty() || role.get().worksOnCode()) continue;
            MemberView agent = members.agents().stream().filter(m -> m.role().equals(roleKey)).findFirst().orElseGet(() -> {
                CatalogApi.AgentSeed seed = catalog.agentSeed(roleKey).orElse(new CatalogApi.AgentSeed(roleKey, "Agente " + role.get().name(), COLORS[0]));
                return members.createAgent(roleKey, seed.memberName(), role.get().description(), seed.color());
            });
            store.addToTeam(event.workspaceId(), event.projectKey(), agent.id());
            AgentTeamStore.Profile p = store.profile(agent.id()).orElse(null);
            if (p == null || p.mode() != AgentMode.SERVER) {
                store.saveProfile(event.workspaceId(), new AgentTeamStore.Profile(agent.id(), AgentMode.SERVER, p == null ? null : p.model(),
                        p == null ? null : p.effort()), clock.instant());
            }
        }
    }

    private TeamAgent view(MemberView m, AgentTeamStore.Profile p, boolean inTeam, AgentPolicy policy) {
        AgentPolicy.Tuning t = policy.tuning(m.role());
        Optional<AgentRole> role = catalog.role(m.role());
        boolean serverAllowed = role.map(r -> !r.worksOnCode()).orElse(true);
        AgentMode mode = p == null || !serverAllowed ? AgentMode.LOCAL : p.mode();
        return new TeamAgent(m.id(), m.name(), m.role(), role.map(AgentRole::name).orElse(m.role()), m.description(), m.avatarColor(), mode,
                p == null ? null : p.model(), p == null ? null : p.effort(), t.model(), t.effort(), inTeam, serverAllowed);
    }

    private MemberView agent(UUID memberId) {
        return members.find(memberId).filter(MemberView::agent).orElseThrow(() -> new NotFoundException("No existe ese agente."));
    }

    private void requireBoard(String projectKey) {
        if (!projects.exists(projectKey)) throw new NotFoundException("No existe el tablero " + projectKey + ".");
    }

    private String cleanModel(String model) {
        if (model == null || model.isBlank()) return null;
        if (!providers.anyOffers(model.strip())) throw new DomainException("Elige uno de los modelos de la lista.");
        return model.strip();
    }
}
