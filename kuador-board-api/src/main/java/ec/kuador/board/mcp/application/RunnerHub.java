package ec.kuador.board.mcp.application;

import ec.kuador.board.issue.IssueEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Avisos a los runners conectados: «puede haber trabajo». No dicen qué ni para quién (eso lo decide el reparto al
 * pedir una tarjeta), así nunca viaja información de un tablero a una máquina que no lo ve. En memoria: con una
 * réplica alcanza y, si la API se reinicia, el runner reconecta y además revisa cada pocos minutos.
 */
@Component
public class RunnerHub {

    static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final Map<UUID, Set<SseEmitter>> byWorkspace = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID workspaceId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        Set<SseEmitter> subscribers = byWorkspace.computeIfAbsent(workspaceId, k -> ConcurrentHashMap.newKeySet());
        subscribers.add(emitter);
        Runnable remove = () -> subscribers.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        // El primero confirma la conexión y hace que el runner revise la cola enseguida.
        send(emitter, SseEmitter.event().name("trabajo").data("{}").reconnectTime(5000), subscribers);
        return emitter;
    }

    /** Algo cambió en el espacio (o se liberó una tarjeta): que los runners vuelvan a pedir. */
    public void signal(UUID workspaceId) {
        Set<SseEmitter> subscribers = byWorkspace.get(workspaceId);
        if (subscribers == null) return;
        subscribers.forEach(e -> send(e, SseEmitter.event().name("trabajo").data("{}"), subscribers));
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    void on(IssueEvent event) {
        signal(event.workspaceId());
    }

    /** Un comentario cada 25 s mantiene viva la conexión a través de proxies que cortan las inactivas. */
    @Scheduled(fixedRate = 25_000, initialDelay = 25_000)
    void heartbeat() {
        byWorkspace.values().forEach(subscribers -> subscribers.forEach(e -> send(e, SseEmitter.event().comment("ping"), subscribers)));
    }

    private static void send(SseEmitter emitter, SseEmitter.SseEventBuilder event, Set<SseEmitter> subscribers) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            subscribers.remove(emitter);
        }
    }
}
