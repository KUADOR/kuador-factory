package ec.kuador.board.shared.cluster;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Una tarea, una sola réplica a la vez. Es un préstamo en la base (no una conexión tomada): quien lo consigue corre la
 * tarea y lo suelta al terminar; si la réplica se cae a mitad, vence solo y otra la retoma. La hora es la de la base,
 * así no importa que los relojes de las réplicas difieran.
 */
@Component
public class ClusterLock {

    private static final Logger log = LoggerFactory.getLogger(ClusterLock.class);

    private final JdbcClient jdbc;
    /** Esta réplica. */
    private final UUID holder = UUID.randomUUID();

    ClusterLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Corre {@code work} si ninguna otra réplica lo está corriendo. {@code atMost}: cuánto puede tardar como mucho; pasado
     * ese tiempo se asume que la réplica murió. Devuelve si corrió.
     */
    public boolean runExclusive(String name, Duration atMost, Runnable work) {
        if (!acquire(name, atMost)) return false;
        try {
            work.run();
            return true;
        } finally {
            release(name);
        }
    }

    private boolean acquire(String name, Duration atMost) {
        return jdbc.sql("""
                insert into cluster_lock (name, holder, locked_until) values (:name, :holder, now() + make_interval(secs => :seconds))
                on conflict (name) do update set holder = excluded.holder, locked_until = excluded.locked_until
                where cluster_lock.locked_until < now()
                """).param("name", name).param("holder", holder).param("seconds", (double) atMost.toSeconds()).update() == 1;
    }

    private void release(String name) {
        try {
            jdbc.sql("update cluster_lock set locked_until = now() where name = :name and holder = :holder").param("name", name).param("holder", holder)
                    .update();
        } catch (RuntimeException e) {
            // No se pudo soltar: vence solo.
            log.warn("No se pudo soltar el candado «{}»: {}", name, e.getMessage());
        }
    }
}
