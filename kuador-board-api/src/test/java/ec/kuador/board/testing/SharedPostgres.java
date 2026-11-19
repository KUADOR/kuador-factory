package ec.kuador.board.testing;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Un solo Postgres para todas las pruebas de integración, con una base nueva y vacía por clase: cada clase sigue
 * aislada, pero el servidor vive hasta el final de la corrida (lo apaga Ryuk al terminar la JVM).
 *
 * <p>Antes cada clase levantaba su contenedor y lo apagaba al terminar. Spring guarda en caché el contexto de la clase
 * anterior y sus tareas programadas seguían intentando conectarse a una base apagada; al pasar a la clase siguiente,
 * Spring esperaba a que esa tarea venciera (30 s de Hikari). En el CI eso eran ~28 s muertos por clase.
 */
public final class SharedPostgres {

    private static final PostgreSQLContainer SERVER = new PostgreSQLContainer("postgres:18-alpine")
            // Los contextos de cada clase quedan en caché con su pool abierto: hacen falta más conexiones que las 100 de fábrica.
            .withCommand("postgres", "-c", "max_connections=400");
    private static final AtomicInteger NEXT = new AtomicInteger();

    static {
        SERVER.start();
    }

    private SharedPostgres() {}

    /** Crea una base vacía para el contexto que se está armando y apunta Spring a ella. */
    public static void register(DynamicPropertyRegistry registry) {
        String name = "it_" + NEXT.incrementAndGet();
        try (Connection c = DriverManager.getConnection(SERVER.getJdbcUrl(), SERVER.getUsername(), SERVER.getPassword());
                Statement s = c.createStatement()) {
            s.execute("create database " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo crear la base de pruebas " + name, e);
        }
        String url = "jdbc:postgresql://" + SERVER.getHost() + ":" + SERVER.getMappedPort(5432) + "/" + name;
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SERVER::getUsername);
        registry.add("spring.datasource.password", SERVER::getPassword);
        // Pocas conexiones por contexto (las pruebas no las necesitan) y, si algo se cuelga, que falle rápido.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
    }
}
