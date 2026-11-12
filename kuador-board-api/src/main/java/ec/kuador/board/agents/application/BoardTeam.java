package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.AgentMode;
import ec.kuador.board.agents.domain.AgentTeamStore;
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
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Equipo de agentes de cada tablero. Un tablero nuevo trae el equipo de su plantilla; después cada tablero suma,
 * crea o quita los suyos. Cada agente trabaja en la PC de una persona (LOCAL, por MCP) o en el tablero con la
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

    BoardTeam(AgentTeamStore store, MemberApi members, ProjectApi projects, CatalogApi catalog, Clock clock, PlatformTransactionManager txManager) {
        super(txManager);
        this.store = store;
        this.members = members;
        this.projects = projects;
        this.catalog = catalog;
        this.clock = clock;
    }

    public record TeamAgent(UUID id, String name, String role, String roleName, String description, String avatarColor, AgentMode mode,
            String model, boolean inTeam) {}

    /** Agentes del tablero primero; después los del espacio que se pueden sumar. */
    @Transactional(readOnly = true)
    public List<TeamAgent> team(String projectKey) {
        UUID ws = TenantContext.require();
        requireBoard(projectKey);
        List<UUID> team = store.team(ws, projectKey);
        Map<UUID, AgentTeamStore.Profile> profiles = store.profiles(ws);
        return members.agents().stream()
                .map(m -> view(m, profiles.get(m.id()), team.contains(m.id())))
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
        UUID ws = TenantContext.require();
        AgentRole role = catalog.role(roleKey).orElseThrow(() -> new DomainException("Elige un rol del catálogo (o créalo en Catálogo → Roles)."));
        String agentName = name == null || name.isBlank() ? "Agente " + role.name() : name.strip();
        MemberView agent = members.createAgent(role.key(), agentName, description == null || description.isBlank() ? role.description() : description.strip(),
                COLORS[(int) (Math.abs(agentName.hashCode()) % COLORS.length)]);
        store.saveProfile(ws, new AgentTeamStore.Profile(agent.id(), mode == null ? AgentMode.LOCAL : mode, cleanModel(model)), clock.instant());
        store.addToTeam(ws, projectKey, agent.id());
        return team(projectKey);
    }

    /** Dónde trabaja el agente y con qué modelo ({@code null} = el del espacio). */
    @Transactional
    public TeamAgent setProfile(UUID memberId, AgentMode mode, String model) {
        MemberView agent = agent(memberId);
        AgentTeamStore.Profile profile = new AgentTeamStore.Profile(memberId, mode == null ? AgentMode.LOCAL : mode, cleanModel(model));
        store.saveProfile(TenantContext.require(), profile, clock.instant());
        return view(agent, profile, false);
    }

    @Transactional(readOnly = true)
    public AgentTeamStore.Profile profile(UUID memberId) {
        return store.profile(memberId).orElse(new AgentTeamStore.Profile(memberId, AgentMode.LOCAL, null));
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
            members.agents().stream().filter(m -> event.teamRoles().contains(m.role())).forEach(m -> {
                store.addToTeam(event.workspaceId(), event.projectKey(), m.id());
                // Fuera de software nadie tiene un Claude Code abierto: el agente trabaja en el tablero (si nadie eligió otra cosa).
                if (!event.software() && !profiles.containsKey(m.id())) {
                    store.saveProfile(event.workspaceId(), new AgentTeamStore.Profile(m.id(), AgentMode.SERVER, null), clock.instant());
                }
            });
        });
    }

    private TeamAgent view(MemberView m, AgentTeamStore.Profile p, boolean inTeam) {
        String roleName = catalog.role(m.role()).map(AgentRole::name).orElse(m.role());
        return new TeamAgent(m.id(), m.name(), m.role(), roleName, m.description(), m.avatarColor(), p == null ? AgentMode.LOCAL : p.mode(),
                p == null ? null : p.model(), inTeam);
    }

    private MemberView agent(UUID memberId) {
        return members.find(memberId).filter(MemberView::agent).orElseThrow(() -> new NotFoundException("No existe ese agente."));
    }

    private void requireBoard(String projectKey) {
        if (!projects.exists(projectKey)) throw new NotFoundException("No existe el tablero " + projectKey + ".");
    }

    private static String cleanModel(String model) {
        if (model == null || model.isBlank()) return null;
        if (!WorkspaceAi.MODELS.contains(model.strip())) throw new DomainException("Elige uno de los modelos de la lista.");
        return model.strip();
    }
}
