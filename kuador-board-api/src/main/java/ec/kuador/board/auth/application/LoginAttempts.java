package ec.kuador.board.auth.application;

import ec.kuador.board.shared.web.RateLimiter;
import java.time.Duration;
import java.time.Instant;

/**
 * Freno a la fuerza bruta en tres niveles, para que nadie pueda bloquear a otra persona con poco esfuerzo:
 * <ul>
 *   <li>el mismo correo desde la misma IP: 5 fallos en 15 minutos;</li>
 *   <li>una IP contra cualquier correo (probar una clave en muchas cuentas): 30 fallos en 15 minutos;</li>
 *   <li>un correo desde cualquier IP (ataque repartido): 50 fallos en una hora.</li>
 * </ul>
 */
class LoginAttempts {

    private final RateLimiter pair = new RateLimiter(5, Duration.ofMinutes(15));
    private final RateLimiter byIp = new RateLimiter(30, Duration.ofMinutes(15));
    private final RateLimiter byEmail = new RateLimiter(50, Duration.ofHours(1));

    boolean isBlocked(String email, String ip, Instant now) {
        return pair.exceeded(email + "|" + ip, now) || (ip != null && byIp.exceeded(ip, now)) || byEmail.exceeded(email, now);
    }

    void recordFailure(String email, String ip, Instant now) {
        pair.record(email + "|" + ip, now);
        if (ip != null) byIp.record(ip, now);
        byEmail.record(email, now);
    }

    void reset(String email, String ip) {
        pair.reset(email + "|" + ip);
    }
}
