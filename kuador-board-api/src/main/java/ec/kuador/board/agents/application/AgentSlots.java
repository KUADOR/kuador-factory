package ec.kuador.board.agents.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cuántos agentes del servidor trabajan a la vez: un tope por espacio (que una empresa con mucho trabajo no deje sin
 * turno a las demás) y uno de toda la instancia. El que no tiene turno espera el suyo; no se descarta.
 */
@Component
class AgentSlots {

    private final Semaphore all;
    private final int perWorkspace;
    private final int max;
    private final Map<UUID, Semaphore> byWorkspace = new ConcurrentHashMap<>();
    private final AtomicInteger waiting = new AtomicInteger();

    AgentSlots(@Value("${board.agents.max-concurrent:24}") int max, @Value("${board.agents.max-per-workspace:6}") int perWorkspace,
            ObjectProvider<MeterRegistry> meters) {
        this.max = Math.max(1, max);
        this.perWorkspace = Math.max(1, perWorkspace);
        this.all = new Semaphore(this.max, true);
        meters.ifAvailable(registry -> {
            Gauge.builder("board.agents.running", this, AgentSlots::running).description("Agentes del servidor trabajando").register(registry);
            Gauge.builder("board.agents.waiting", waiting, AtomicInteger::get).description("Agentes esperando turno").register(registry);
        });
    }

    /** Corre {@code work} cuando hay turno. Primero el del espacio: quien espera el suyo no ocupa uno de todos. */
    void run(UUID workspaceId, Runnable work) {
        Semaphore own = byWorkspace.computeIfAbsent(workspaceId, k -> new Semaphore(perWorkspace, true));
        waiting.incrementAndGet();
        boolean counted = true;
        try {
            own.acquire();
            try {
                all.acquire();
                try {
                    waiting.decrementAndGet();
                    counted = false;
                    work.run();
                } finally {
                    all.release();
                }
            } finally {
                own.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Sin terminar: el evento queda registrado y se reentrega al arrancar.
            throw new IllegalStateException("La API se detuvo mientras el agente esperaba turno.", e);
        } finally {
            if (counted) waiting.decrementAndGet();
        }
    }

    int running() {
        return max - all.availablePermits();
    }

    int waiting() {
        return waiting.get();
    }
}
