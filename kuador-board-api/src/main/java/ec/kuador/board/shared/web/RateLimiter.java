package ec.kuador.board.shared.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ventana deslizante en memoria: cuántos eventos tuvo una clave (un correo, una IP) en {@code window}. Vale para
 * una sola instancia de la API (y para las pruebas); con varias réplicas se usa el de {@link RateLimits}, que cuenta
 * en la base. Se limpia sola para no crecer sin tope con claves inventadas.
 */
public class RateLimiter {

    private static final int MAX_KEYS = 100_000;

    private final int max;
    private final Duration window;
    private final Map<String, Deque<Instant>> events = new ConcurrentHashMap<>();
    private int sinceCleanup;

    public RateLimiter(int max, Duration window) {
        this.max = max;
        this.window = window;
    }

    public boolean exceeded(String key, Instant now) {
        Deque<Instant> list = events.get(key);
        if (list == null) return false;
        synchronized (list) {
            prune(list, now);
            return list.size() >= max;
        }
    }

    public void record(String key, Instant now) {
        cleanup(now);
        Deque<Instant> list = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (list) {
            prune(list, now);
            list.addLast(now);
        }
    }

    /** Registra y dice si ya se pasó del límite (para «un intento más» de una sola llamada). */
    public boolean hit(String key, Instant now) {
        if (exceeded(key, now)) return true;
        record(key, now);
        return false;
    }

    public void reset(String key) {
        events.remove(key);
    }

    private synchronized void cleanup(Instant now) {
        if (++sinceCleanup < 1_000 && events.size() < MAX_KEYS) return;
        sinceCleanup = 0;
        events.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), now);
                return e.getValue().isEmpty();
            }
        });
        if (events.size() >= MAX_KEYS) events.clear(); // inundación de claves: se empieza de nuevo antes que quedarse sin memoria
    }

    private void prune(Deque<Instant> list, Instant now) {
        while (!list.isEmpty() && !list.peekFirst().isAfter(now.minus(window))) list.pollFirst();
    }
}
