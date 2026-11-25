package ec.kuador.board.live.application;

import java.io.IOException;
import java.time.Duration;
import ec.kuador.board.shared.cluster.ClusterBus;
import ec.kuador.board.shared.tenant.BoardScope;
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
 * Conexiones abiertas por espacio. Las conexiones viven en la réplica que las atiende; lo que cambia se cuenta a las
 * demás réplicas ({@link ClusterBus}) para que cada una avise a las suyas. Si la API se reinicia, el navegador se
 * reconecta solo (EventSource) y recarga.
 */
@Component
public class LiveHub {

    private static final Logger log = LoggerFactory.getLogger(LiveHub.class);
    /** Cada tanto se corta y el navegador reabre: así no quedan conexiones colgadas para siempre. */
    static final Duration TIMEOUT = Duration.ofMinutes(30);

    /** Qué cambió. {@code issueKey} nulo = algo del proyecto (p. ej. un documento del proyecto). */
    public record Change(String projectKey, String issueKey, String type, UUID actorId) {}

    /** {@code visible}: los tableros que puede ver (nulo = todos), tomados al conectarse. */
    private record Subscriber(SseEmitter emitter, String projectKey, Set<String> visible) {

        boolean wants(String changed) {
            return (projectKey == null || projectKey.equals(changed)) && (visible == null || visible.contains(changed));
        }
    }

    private static final String TOPIC = "tablero";

    private final Map<UUID, Set<Subscriber>> byWorkspace = new ConcurrentHashMap<>();
    private final ClusterBus bus;

    LiveHub(ClusterBus bus) {
        this.bus = bus;
        // De otra réplica: «espacio|proyecto|datos».
        bus.subscribe(TOPIC, message -> {
            String[] parts = message.split("\\|", 3);
            if (parts.length == 3) deliver(UUID.fromString(parts[0]), parts[1], parts[2]);
        });
    }

    /** {@code projectKey} nulo = todos los proyectos que puede ver quien pide (ver {@link BoardScope}). */
    public SseEmitter subscribe(UUID workspaceId, String projectKey) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        Subscriber s = new Subscriber(emitter, projectKey, BoardScope.current().orElse(null));
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
        String data = "{\"proyecto\":\"%s\",\"issue\":%s,\"tipo\":\"%s\",\"actor\":%s}".formatted(change.projectKey(),
                change.issueKey() == null ? "null" : "\"" + change.issueKey() + "\"", change.type(),
                change.actorId() == null ? "null" : "\"" + change.actorId() + "\"");
        deliver(workspaceId, change.projectKey(), data);
        bus.publish(TOPIC, workspaceId + "|" + change.projectKey() + "|" + data);
    }

    /** A quienes están conectados a ESTA réplica. */
    private void deliver(UUID workspaceId, String projectKey, String data) {
        Set<Subscriber> subscribers = byWorkspace.get(workspaceId);
        if (subscribers == null) return;
        for (Subscriber s : subscribers) {
            if (s.wants(projectKey)) send(s, SseEmitter.event().name("cambio").data(data), subscribers);
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
