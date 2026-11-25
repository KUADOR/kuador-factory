package ec.kuador.board.agents.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Los turnos de los agentes: un espacio con mucho trabajo espera el suyo sin dejar sin turno a los demás. */
class AgentSlotsTest {

    private static AgentSlots slots(int max, int perWorkspace) {
        return new AgentSlots(max, perWorkspace, new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
    }

    @Test
    void aBusyWorkspaceWaitsItsTurnWithoutBlockingAnother() throws Exception {
        AgentSlots slots = slots(10, 2);
        UUID busy = UUID.randomUUID();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger working = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(5);
        for (int i = 0; i < 5; i++) {
            Thread.ofVirtual().start(() -> slots.run(busy, () -> {
                peak.accumulateAndGet(working.incrementAndGet(), Math::max);
                await(release);
                working.decrementAndGet();
                done.countDown();
            }));
        }
        eventually(() -> slots.running() == 2 && slots.waiting() == 3);

        // Otro espacio entra de inmediato, aunque el primero tenga cola.
        CountDownLatch other = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> slots.run(UUID.randomUUID(), other::countDown));
        assertThat(other.await(2, TimeUnit.SECONDS)).as("el otro espacio no espera").isTrue();

        release.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).as("los que esperaban terminan").isTrue();
        assertThat(peak.get()).as("nunca más de dos a la vez por espacio").isEqualTo(2);
        assertThat(slots.running()).isZero();
        assertThat(slots.waiting()).isZero();
    }

    @Test
    void theInstanceLimitHoldsAcrossWorkspaces() throws Exception {
        AgentSlots slots = slots(2, 2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            Thread.ofVirtual().start(() -> slots.run(UUID.randomUUID(), () -> {
                await(release);
                done.countDown();
            }));
        }
        eventually(() -> slots.running() == 2 && slots.waiting() == 1);
        release.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aFailingAgentGivesItsTurnBack() {
        AgentSlots slots = slots(1, 1);
        UUID workspace = UUID.randomUUID();
        try {
            slots.run(workspace, () -> {
                throw new IllegalStateException("falló");
            });
        } catch (IllegalStateException expected) {
            // sigue
        }
        AtomicInteger ran = new AtomicInteger();
        slots.run(workspace, ran::incrementAndGet);
        assertThat(ran.get()).isEqualTo(1);
        assertThat(slots.waiting()).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) Thread.sleep(20);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
