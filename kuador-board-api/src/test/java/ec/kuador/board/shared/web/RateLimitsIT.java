package ec.kuador.board.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Los frenos cuentan en la base: dos réplicas ven los mismos intentos. */
@SpringBootTest(properties = {"board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class RateLimitsIT {

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        ec.kuador.board.testing.SharedPostgres.register(registry);
    }

    @Autowired RateLimits limits;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void attemptsSpreadOverTwoReplicasAddUp() {
        // Cada réplica arma su propio freno con el mismo nombre.
        RateLimiter a = limits.limiter("prueba-login", 5, Duration.ofMinutes(15));
        RateLimiter b = limits.limiter("prueba-login", 5, Duration.ofMinutes(15));
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            RateLimiter replica = i % 2 == 0 ? a : b;
            assertThat(replica.exceeded("ana@x.test|1.2.3.4", now)).as("intento " + (i + 1)).isFalse();
            replica.record("ana@x.test|1.2.3.4", now.plusSeconds(i));
        }
        assertThat(a.exceeded("ana@x.test|1.2.3.4", now.plusSeconds(10))).isTrue();
        assertThat(b.exceeded("ana@x.test|1.2.3.4", now.plusSeconds(10))).isTrue();
        assertThat(b.exceeded("otra@x.test|1.2.3.4", now.plusSeconds(10))).as("otra clave no se bloquea").isFalse();
        assertThat(limits.limiter("otro-freno", 5, Duration.ofMinutes(15)).exceeded("ana@x.test|1.2.3.4", now.plusSeconds(10)))
                .as("cada freno cuenta lo suyo").isFalse();

        assertThat(a.exceeded("ana@x.test|1.2.3.4", now.plus(Duration.ofMinutes(16)))).as("pasada la ventana, se libera").isFalse();
        b.reset("ana@x.test|1.2.3.4");
        assertThat(a.exceeded("ana@x.test|1.2.3.4", now.plusSeconds(10))).as("al entrar bien se borra").isFalse();
    }

    @Test
    void aFailedRequestStillCountsItsAttempt() {
        RateLimiter limiter = limits.limiter("prueba-rollback", 1, Duration.ofMinutes(15));
        Instant now = Instant.now();
        try {
            new TransactionTemplate(txManager).executeWithoutResult(s -> {
                limiter.record("clave", now);
                throw new IllegalStateException("la petición falla y deshace su transacción");
            });
        } catch (IllegalStateException expected) {
            // sigue
        }
        assertThat(limiter.exceeded("clave", now.plusSeconds(1))).isTrue();
        assertThat(limiter.hit("clave", now.plusSeconds(1))).isTrue();
    }
}
