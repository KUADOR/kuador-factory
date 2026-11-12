package ec.kuador.board.agents.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Perfil de cada agente (modo y modelo), equipo de cada tablero y clave de IA del espacio. */
public interface AgentTeamStore {

    record Profile(UUID memberId, AgentMode mode, String model) {}

    /** Perfiles guardados del espacio (sin fila = LOCAL y modelo por defecto). */
    Map<UUID, Profile> profiles(UUID workspaceId);

    Optional<Profile> profile(UUID memberId);

    void saveProfile(UUID workspaceId, Profile profile, Instant now);

    List<UUID> team(UUID workspaceId, String projectKey);

    /** Tableros donde está un agente. */
    List<String> boardsOf(UUID workspaceId, UUID memberId);

    void addToTeam(UUID workspaceId, String projectKey, UUID memberId);

    void removeFromTeam(UUID workspaceId, String projectKey, UUID memberId);

    record AiKey(String encryptedKey, String hint, String defaultModel) {}

    Optional<AiKey> aiKey(UUID workspaceId);

    void saveAiKey(UUID workspaceId, AiKey key, Instant now);

    void deleteAiKey(UUID workspaceId);
}
