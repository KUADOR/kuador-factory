package ec.kuador.board.project.domain;

import java.util.List;
import java.util.UUID;

/** Puerto de salida: qué personas están en cada tablero del espacio actual. */
public interface BoardPeople {

    List<UUID> of(String projectKey);

    List<String> boardsOf(UUID memberId);

    /** Tableros en cuyo equipo de agentes está este agente (project_agent). */
    List<String> agentBoardsOf(UUID agentId);

    boolean contains(String projectKey, UUID memberId);

    void add(String projectKey, UUID memberId);

    void remove(String projectKey, UUID memberId);
}
