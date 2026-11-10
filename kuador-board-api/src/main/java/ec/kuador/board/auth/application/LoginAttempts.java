package ec.kuador.board.auth.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Freno a la fuerza bruta: tras {@code maxFailures} fallos en {@code window} para el mismo correo,
 * se bloquea el login de ese correo hasta que los fallos salgan de la ventana. En memoria: basta
 * con una sola instancia de la API.
 */
class LoginAttempts {

    private final int maxFailures;
    private final Duration window;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    LoginAttempts(int maxFailures, Duration window) {
        this.maxFailures = maxFailures;
        this.window = window;
    }

    boolean isBlocked(String email, Instant now) {
        Deque<Instant> list = failures.get(email);
        if (list == null) return false;
        synchronized (list) {
            prune(list, now);
            return list.size() >= maxFailures;
        }
    }

    void recordFailure(String email, Instant now) {
        Deque<Instant> list = failures.computeIfAbsent(email, k -> new ArrayDeque<>());
        synchronized (list) {
            prune(list, now);
            list.addLast(now);
        }
    }

    void reset(String email) {
        failures.remove(email);
    }

    private void prune(Deque<Instant> list, Instant now) {
        while (!list.isEmpty() && !list.peekFirst().isAfter(now.minus(window))) {
            list.pollFirst();
        }
    }
}
