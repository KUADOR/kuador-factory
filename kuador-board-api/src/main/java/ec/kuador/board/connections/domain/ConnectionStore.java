package ec.kuador.board.connections.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puerto: las conexiones de un espacio y en qué tableros (y para qué roles) se usan. */
public interface ConnectionStore {

    /** Uso de una conexión en un tablero. {@code roles} vacío = cualquier rol del tablero. */
    record Use(String connection, String projectKey, List<String> roles) {}

    List<Connection> all(UUID workspaceId);

    Optional<Connection> find(UUID workspaceId, String key);

    void save(UUID workspaceId, Connection connection);

    void delete(UUID workspaceId, String key);

    List<Use> uses(UUID workspaceId);

    List<Use> usesOf(UUID workspaceId, String projectKey);

    /** Reemplaza los usos de un tablero. */
    void saveUses(UUID workspaceId, String projectKey, List<Use> uses);

    void deleteUsesOfProject(UUID workspaceId, String projectKey);
}
