package ec.kuador.board.agents.application;

import ec.kuador.board.agents.domain.AgentRun;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Las ejecuciones que ESTA réplica tiene corriendo en el servidor. Mientras están aquí, se les renueva la señal de vida;
 * si la réplica se cae, dejan de renovarse y otra las da por interrumpidas.
 */
@Component
public class LiveRuns {

    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    /** Lo llama quien guarda una ejecución: entra al empezar y sale al terminar. */
    public void track(AgentRun run) {
        if (run.status() == AgentRun.Status.RUNNING && !run.isLocal()) running.add(run.id());
        else running.remove(run.id());
    }

    public Set<UUID> ids() {
        return Set.copyOf(running);
    }
}
