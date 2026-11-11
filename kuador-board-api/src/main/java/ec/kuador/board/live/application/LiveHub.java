package ec.kuador.board.live.application;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Conexiones abiertas por espacio. Vive en memoria: con una sola réplica alcanza, y si la API se
 * reinicia el navegador se reconecta solo (EventSource) y recarga.
 */
@Component
public class LiveHub {

    private static final Logger log = LoggerFactory.getLogger(LiveHub.class);
    /** Cada tanto se corta y el navegador reabre: así no quedan conexiones colgadas para siempre. */
    static final Duration TIMEOUT = Duration.ofMinutes(30);

    /** Qué cambió. {@code issueKey} nulo = algo del proyecto (p. ej. un documento del proyecto). */
    public record Change(String projectKey, String issueKey, String type, UUID actorId) {}

    private record Subscriber(SseEmitter emitter, String projectKey) {}

    private final Map<UUID, Set<Subscriber>> byWorkspace = new ConcurrentHashMap<>();

    /** {@code projectKey} nulo = todos los proyectos del espacio. */
    public SseEmitter subscribe(UUID workspaceId, String projectKey) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        Subscriber s = new Subscriber(emitter, projectKey);
        Set<Subscriber> subscribers = byWorkspace.computeIfAbsent(workspaceId, k -> ConcurrentHashMap.newKeySet());
        subscribers.add(s);
        Runnable remove = () -> subscribers.remove(s);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        // Primer mensaje: confirma la conexión y le dice al navegador cada cuánto reintentar si se corta.
        send(s, SseEmitter.event().name("listo").data("{}").reconnectTime(3000), subscribers);
        return emitter;
    }

    public void publish(UUID workspaceId, Change change) {
        Set<Subscriber> subscribers = byWorkspace.get(workspaceId);
        if (subscribers == null) return;
        String data = "{\"proyecto\":\"%s\",\"issue\":%s,\"tipo\":\"%s\",\"actor\":%s}".formatted(change.projectKey(),
                change.issueKey() == null ? "null" : "\"" + change.issueKey() + "\"", change.type(),
                change.actorId() == null ? "null" : "\"" + change.actorId() + "\"");
        for (Subscriber s : subscribers) {
            if (s.projectKey() == null || s.projectKey().equals(change.projectKey())) {
                send(s, SseEmitter.event().name("cambio").data(data), subscribers);
            }
        }
    }

    /** Un comentario cada 25 s mantiene viva la conexión a través de proxies que cortan las inactivas. */
    @Scheduled(fixedRate = 25_000, initialDelay = 25_000)
    void heartbeat() {
        byWorkspace.values().forEach(subscribers -> subscribers.forEach(s -> send(s, SseEmitter.event().comment("ping"), subscribers)));
    }

    int connections() {
        return byWorkspace.values().stream().mapToInt(Set::size).sum();
    }

    private static void send(Subscriber s, SseEmitter.SseEventBuilder event, Set<Subscriber> subscribers) {
        try {
            s.emitter().send(event);
        } catch (IOException | IllegalStateException e) {
            // El navegador se fue: se descarta la conexión.
            subscribers.remove(s);
            log.debug("Conexión en tiempo real cerrada: {}", e.getMessage());
        }
    }
}
