package ec.kuador.board.shared.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Dos réplicas contra la misma base: lo que publica una le llega a la otra, y a ella misma no. */
@SpringBootTest(properties = {"board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class ClusterBusIT {

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        ec.kuador.board.testing.SharedPostgres.register(registry);
    }

    @Autowired JdbcClient jdbc;
    @Value("${spring.datasource.url}") String url;
    @Value("${spring.datasource.username}") String username;
    @Value("${spring.datasource.password}") String password;

    private ClusterBus replica() {
        return new ClusterBus(jdbc, url, username, password, true);
    }

    @Test
    void whatOneReplicaPublishesReachesTheOther() throws Exception {
        ClusterBus a = replica();
        ClusterBus b = replica();
        List<String> atA = new CopyOnWriteArrayList<>();
        List<String> atB = new CopyOnWriteArrayList<>();
        List<String> otherTopic = new CopyOnWriteArrayList<>();
        a.subscribe("tablero", atA::add);
        b.subscribe("tablero", atB::add);
        b.subscribe("runners", otherTopic::add);
        a.start();
        b.start();
        try {
            eventually("las dos escuchan", () -> a.listening() && b.listening());

            a.publish("tablero", "espacio|PLA|{\"tipo\":\"MOVIDO\"}");
            eventually("le llega a la otra réplica", () -> atB.size() == 1);
            assertThat(atB).containsExactly("espacio|PLA|{\"tipo\":\"MOVIDO\"}");

            b.publish("tablero", "de vuelta");
            eventually("y en el otro sentido", () -> atA.size() == 1);
            assertThat(atA).as("quien publica no recibe lo suyo").containsExactly("de vuelta");
            assertThat(atB).hasSize(1);
            assertThat(otherTopic).as("cada tema por separado").isEmpty();
        } finally {
            a.stop();
            b.stop();
        }
    }

    private static void eventually(String what, BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) Thread.sleep(50);
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }
}
