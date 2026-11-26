package ec.kuador.board.project.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puerto de salida: los equipos del espacio actual, quién está en cada uno y qué tableros atiende. */
public interface TeamRepository {

    List<Team> findAll();

    Optional<Team> find(UUID id);

    /** Si otro equipo del espacio ya se llama así (sin distinguir mayúsculas). */
    boolean nameTaken(String name, UUID except);

    void insert(Team team);

    void rename(UUID id, String name);

    void delete(UUID id);

    /** Personas y agentes del equipo, en el orden en que se sumaron. */
    List<UUID> members(UUID teamId);

    void addMember(UUID teamId, UUID memberId);

    void removeMember(UUID teamId, UUID memberId);

    /** Los tableros que atiende el equipo. */
    List<String> boards(UUID teamId);

    /** Los equipos donde está un miembro. */
    List<UUID> teamsOf(UUID memberId);

    Optional<UUID> teamOf(String projectKey);

    void assign(String projectKey, UUID teamId);
}
