package ec.kuador.board.shared.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Los mismos frenos de {@link RateLimiter}, contados en la base: valen entre todas las réplicas de la API y sobreviven
 * a un reinicio. Cada freno tiene su nombre ({@code bucket}). Se anota en su propia transacción: si la petición que
 * falló deshace la suya, el intento igual queda contado.
 */
@Component
public class RateLimits {

    private static final Logger log = LoggerFactory.getLogger(RateLimits.class);
    /** La ventana más larga de un freno es de un día. */
    private static final Duration KEEP = Duration.ofDays(2);

    private final JdbcClient jdbc;
    private final TransactionTemplate ownTx;
    private final Clock clock;

    RateLimits(JdbcClient jdbc, PlatformTransactionManager txManager, Clock clock) {
        this.jdbc = jdbc;
        this.ownTx = new TransactionTemplate(txManager);
        this.ownTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public RateLimiter limiter(String bucket, int max, Duration window) {
        return new Shared(bucket, max, window);
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 900_000)
    void cleanup() {
        try {
            jdbc.sql("delete from rate_hit where at < ?").param(Timestamp.from(clock.instant().minus(KEEP))).update();
        } catch (RuntimeException e) {
            log.warn("No se pudieron limpiar los intentos viejos: {}", e.getMessage());
        }
    }

    private final class Shared extends RateLimiter {

        private final String bucket;
        private final int max;
        private final Duration window;

        Shared(String bucket, int max, Duration window) {
            super(max, window);
            this.bucket = bucket;
            this.max = max;
            this.window = window;
        }

        @Override
        public boolean exceeded(String key, Instant now) {
            Integer count = ownTx.execute(s -> jdbc.sql("select count(*) from rate_hit where bucket = ? and key_hash = ? and at > ?")
                    .params(bucket, hash(key), Timestamp.from(now.minus(window))).query(Integer.class).single());
            return count != null && count >= max;
        }

        @Override
        public void record(String key, Instant now) {
            ownTx.executeWithoutResult(s -> jdbc.sql("insert into rate_hit (bucket, key_hash, at) values (?, ?, ?)")
                    .params(bucket, hash(key), Timestamp.from(now)).update());
        }

        @Override
        public void reset(String key) {
            ownTx.executeWithoutResult(s -> jdbc.sql("delete from rate_hit where bucket = ? and key_hash = ?").params(bucket, hash(key)).update());
        }
    }

    private static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.valueOf(key).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
