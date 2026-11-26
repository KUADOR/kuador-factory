package ec.kuador.board.project.application;

import ec.kuador.board.member.MemberApi;
import ec.kuador.board.member.MemberView;
import ec.kuador.board.project.domain.ProjectRepository;
import ec.kuador.board.project.domain.Team;
import ec.kuador.board.project.domain.TeamRepository;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.BoardScope;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los equipos del espacio: las personas y los agentes que atienden uno o varios tableros. Un tablero tiene un equipo; si
 * alguien de otro equipo ayuda, se suma a ese equipo. Arma los equipos quien administra (lo fija la seguridad).
 */
@Service
public class TeamService {

    private final TeamRepository teams;
    private final ProjectRepository projects;
    private final MemberApi members;
    private final Clock clock;

    TeamService(TeamRepository teams, ProjectRepository projects, MemberApi members, Clock clock) {
        this.teams = teams;
        this.projects = projects;
        this.members = members;
        this.clock = clock;
    }

    /** Alguien del equipo: una persona o un agente ({@code agent}). */
    public record TeamMember(UUID id, String name, String role, String avatarColor, boolean agent) {}

    /** El equipo con su gente y los tableros que atiende (solo los que puede ver quien pide). */
    public record TeamView(UUID id, String name, Instant createdAt, List<TeamMember> members, List<String> boards) {}

    /** El equipo de un tablero, para mostrarlo con el tablero. */
    public record TeamRef(UUID id, String name) {}

    /** Quien administra ve todos los equipos; los demás, los suyos. */
    @Transactional(readOnly = true)
    public List<TeamView> list(UUID actorId) {
        boolean all = administers(actorId);
        java.util.Set<UUID> mine = all ? java.util.Set.of() : java.util.Set.copyOf(teams.teamsOf(actorId));
        Map<UUID, MemberView> people = everyone();
        return teams.findAll().stream().filter(t -> all || mine.contains(t.id())).map(t -> view(t, people)).toList();
    }

    @Transactional(readOnly = true)
    public TeamView get(UUID id, UUID actorId) {
        Team team = load(id);
        if (!administers(actorId) && !teams.teamsOf(actorId).contains(id)) throw new NotFoundException("No existe ese equipo.");
        return view(team, everyone());
    }

    @Transactional
    public TeamView create(String name, List<UUID> memberIds) {
        Team team = Team.create(name, clock.instant());
        requireFreeName(team.name(), null);
        teams.insert(team);
        if (memberIds != null) memberIds.stream().distinct().forEach(m -> teams.addMember(team.id(), member(m).id()));
        return view(team, everyone());
    }

    @Transactional
    public TeamView rename(UUID id, String name) {
        Team renamed = load(id).withName(name);
        requireFreeName(renamed.name(), id);
        teams.rename(id, renamed.name());
        return view(renamed, everyone());
    }

    /** Un equipo se borra solo cuando no atiende ningún tablero. */
    @Transactional
    public void delete(UUID id) {
        Team team = load(id);
        List<String> boards = teams.boards(id);
        if (!boards.isEmpty()) {
            throw new DomainException("«" + team.name() + "» atiende " + (boards.size() == 1 ? "el tablero " : "los tableros ")
                    + String.join(", ", boards) + ": dales otro equipo antes de borrarlo.");
        }
        teams.delete(id);
    }

    @Transactional
    public TeamView addMember(UUID id, UUID memberId) {
        Team team = load(id);
        teams.addMember(team.id(), member(memberId).id());
        return view(team, everyone());
    }

    @Transactional
    public TeamView removeMember(UUID id, UUID memberId) {
        Team team = load(id);
        teams.removeMember(team.id(), memberId);
        return view(team, everyone());
    }

    /** Cambia el equipo que atiende un tablero. */
    @Transactional
    public TeamRef assign(String projectKey, UUID teamId) {
        String board = projects.findByKey(projectKey.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NotFoundException("No existe el tablero " + projectKey + ".")).key();
        Team team = load(teamId);
        teams.assign(board, team.id());
        return new TeamRef(team.id(), team.name());
    }

    @Transactional(readOnly = true)
    public boolean exists(UUID id) {
        return id != null && teams.find(id).isPresent();
    }

    @Transactional(readOnly = true)
    public java.util.Optional<TeamRef> teamOf(String projectKey) {
        return teams.teamOf(projectKey).flatMap(teams::find).map(t -> new TeamRef(t.id(), t.name()));
    }

    /**
     * El equipo de un tablero recién creado: el elegido o, si no se eligió, uno nuevo con el nombre del tablero (y quien
     * lo crea adentro). Quien no administra solo elige entre sus equipos: así no se da acceso a otros sin querer.
     */
    UUID teamForNewBoard(String boardKey, String boardName, UUID teamId, UUID creatorId) {
        if (teamId != null) {
            Team team = load(teamId);
            if (creatorId != null && !administers(creatorId) && members.find(creatorId).filter(m -> !m.agent()).isPresent()
                    && !teams.teamsOf(creatorId).contains(team.id())) {
                throw new NotFoundException("No existe ese equipo.");
            }
            teams.assign(boardKey, team.id());
            return team.id();
        }
        Team team = Team.create(freeName(boardName, boardKey), clock.instant());
        teams.insert(team);
        teams.assign(boardKey, team.id());
        if (creatorId != null && members.find(creatorId).filter(m -> !m.agent()).isPresent()) teams.addMember(team.id(), creatorId);
        return team.id();
    }

    /** El nombre del tablero, o con su clave si otro equipo ya se llama así. */
    private String freeName(String boardName, String boardKey) {
        String base = boardName.strip();
        if (base.length() > Team.MAX_NAME) base = base.substring(0, Team.MAX_NAME);
        if (!teams.nameTaken(base, null)) return base;
        String withKey = base + " (" + boardKey + ")";
        for (int n = 2; teams.nameTaken(withKey, null); n++) withKey = base + " (" + boardKey + " " + n + ")";
        return withKey;
    }

    private void requireFreeName(String name, UUID except) {
        if (teams.nameTaken(name, except)) throw new ConflictException("Ya hay un equipo que se llama «" + name + "».");
    }

    private TeamView view(Team t, Map<UUID, MemberView> people) {
        List<TeamMember> list = teams.members(t.id()).stream().map(people::get).filter(Objects::nonNull)
                .map(m -> new TeamMember(m.id(), m.name(), m.role(), m.avatarColor(), m.agent())).toList();
        return new TeamView(t.id(), t.name(), t.createdAt(), list, teams.boards(t.id()).stream().filter(BoardScope::allows).toList());
    }

    private Map<UUID, MemberView> everyone() {
        return java.util.stream.Stream.concat(members.humans().stream(), members.agents().stream())
                .collect(Collectors.toMap(MemberView::id, Function.identity(), (a, b) -> a));
    }

    private boolean administers(UUID actorId) {
        return actorId != null && members.find(actorId).map(MemberView::administers).orElse(false);
    }

    private MemberView member(UUID id) {
        return members.find(id).orElseThrow(() -> new NotFoundException("No existe esa persona o agente en el espacio."));
    }

    private Team load(UUID id) {
        if (id == null) throw new NotFoundException("No existe ese equipo.");
        return teams.find(id).orElseThrow(() -> new NotFoundException("No existe ese equipo."));
    }
}
