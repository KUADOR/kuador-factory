package ec.kuador.board.shared.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Dos réplicas contra la misma base: una tarea programada la corre una sola a la vez. */
@SpringBootTest(properties = {"board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class ClusterLockIT {

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        ec.kuador.board.testing.SharedPostgres.register(registry);
    }

    @Autowired JdbcClient jdbc;

    @Test
    void onlyOneReplicaRunsTheTaskAtATime() throws Exception {
        ClusterLock a = new ClusterLock(jdbc);
        ClusterLock b = new ClusterLock(jdbc);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();

        Thread first = Thread.ofVirtual().start(() -> a.runExclusive("tarea", Duration.ofMinutes(5), () -> {
            runs.incrementAndGet();
            started.countDown();
            await(finish);
        }));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(b.runExclusive("tarea", Duration.ofMinutes(5), runs::incrementAndGet)).as("la otra réplica la está corriendo").isFalse();
        assertThat(b.runExclusive("otra-tarea", Duration.ofMinutes(5), runs::incrementAndGet)).as("cada tarea tiene su candado").isTrue();

        finish.countDown();
        first.join(5000);
        assertThat(b.runExclusive("tarea", Duration.ofMinutes(5), runs::incrementAndGet)).as("al terminar, queda libre").isTrue();
        assertThat(runs.get()).isEqualTo(3);
    }

    @Test
    void aTaskThatFailsStillReleasesItsLock() {
        ClusterLock a = new ClusterLock(jdbc);
        ClusterLock b = new ClusterLock(jdbc);
        try {
            a.runExclusive("falla", Duration.ofMinutes(5), () -> {
                throw new IllegalStateException("falló");
            });
        } catch (IllegalStateException expected) {
            // sigue
        }
        assertThat(b.runExclusive("falla", Duration.ofMinutes(5), () -> {})).isTrue();
    }

    @Test
    void aReplicaThatDiedLosesItsLockWhenItExpires() throws Exception {
        ClusterLock dead = new ClusterLock(jdbc);
        ClusterLock alive = new ClusterLock(jdbc);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        // Una réplica que se quedó colgada con un préstamo de 1 segundo.
        Thread.ofVirtual().start(() -> dead.runExclusive("colgada", Duration.ofSeconds(1), () -> {
            started.countDown();
            await(never);
        }));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(alive.runExclusive("colgada", Duration.ofMinutes(5), () -> {})).as("todavía vigente").isFalse();
        Thread.sleep(1300);
        assertThat(alive.runExclusive("colgada", Duration.ofMinutes(5), () -> {})).as("venció: otra la retoma").isTrue();
        never.countDown();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
