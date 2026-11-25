package ec.kuador.board.shared.cluster;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Mensajes cortos entre las réplicas de la API, por LISTEN/NOTIFY de Postgres: lo que una réplica sabe y las demás
 * deben contarle a quien tienen conectado (el tablero en vivo, el aviso a los runners). Es «a lo mejor llega»: si se
 * pierde uno, el navegador y el runner igual recargan al reconectar. Quien publica no recibe su propio mensaje.
 */
@Component
public class ClusterBus implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ClusterBus.class);
    private static final String CHANNEL = "kuador_bus";
    /** Postgres admite hasta 8000 bytes por aviso; lo nuestro son claves y tipos. */
    private static final int MAX_PAYLOAD = 7000;

    private final JdbcClient jdbc;
    private final String url;
    private final String username;
    private final String password;
    private final boolean enabled;
    /** Esta réplica. */
    private final String self = UUID.randomUUID().toString();
    private final Map<String, List<Consumer<String>>> handlers = new ConcurrentHashMap<>();

    private volatile boolean running;
    private volatile boolean listening;
    private volatile Connection connection;
    private Thread thread;

    ClusterBus(JdbcClient jdbc, @Value("${spring.datasource.url}") String url, @Value("${spring.datasource.username:}") String username,
            @Value("${spring.datasource.password:}") String password, @Value("${board.cluster.bus:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.url = url;
        this.username = username;
        this.password = password;
        this.enabled = enabled;
    }

    /** Lo que llega de OTRA réplica con ese tema. Corre en el hilo que escucha: que sea corto. */
    public void subscribe(String topic, Consumer<String> handler) {
        handlers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /** Lo cuenta a las demás réplicas. Si no se puede, no falla: quien publica ya atendió a los suyos. */
    public void publish(String topic, String payload) {
        if (!enabled) return;
        String message = self + "|" + topic + "|" + payload;
        if (message.length() > MAX_PAYLOAD) {
            log.warn("Mensaje de «{}» demasiado largo para avisar a las otras réplicas ({} caracteres)", topic, message.length());
            return;
        }
        try {
            jdbc.sql("select pg_notify(?, ?)").params(CHANNEL, message).query((rs, i) -> 0).list();
        } catch (RuntimeException e) {
            log.debug("No se pudo avisar a las otras réplicas ({}): {}", topic, e.getMessage());
        }
    }

    /** Ya está escuchando (para las pruebas y la salud). */
    public boolean listening() {
        return listening;
    }

    @Override
    public void start() {
        if (!enabled || running) return;
        running = true;
        thread = Thread.ofPlatform().daemon().name("replicas-escucha").start(this::listen);
    }

    @Override
    public void stop() {
        running = false;
        listening = false;
        close(connection);
        if (thread != null) thread.interrupt();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Una conexión propia, fuera del pool: queda abierta escuchando. Si se corta, se reabre. */
    private void listen() {
        while (running) {
            try (Connection c = DriverManager.getConnection(url, username, password)) {
                connection = c;
                try (Statement s = c.createStatement()) {
                    s.execute("listen " + CHANNEL);
                }
                PGConnection pg = c.unwrap(PGConnection.class);
                listening = true;
                while (running) {
                    PGNotification[] received = pg.getNotifications(10_000);
                    if (received == null) continue;
                    for (PGNotification n : received) deliver(n.getParameter());
                }
            } catch (SQLException | RuntimeException e) {
                listening = false;
                if (!running) return;
                log.warn("Se cortó la escucha entre réplicas ({}): se reintenta", e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    private void deliver(String message) {
        String[] parts = message.split("\\|", 3);
        if (parts.length < 3 || parts[0].equals(self)) return;
        for (Consumer<String> handler : handlers.getOrDefault(parts[1], List.of())) {
            try {
                handler.accept(parts[2]);
            } catch (RuntimeException e) {
                log.warn("Falló un aviso de otra réplica ({}): {}", parts[1], e.getMessage());
            }
        }
    }

    private static void close(Connection c) {
        if (c == null) return;
        try {
            c.close();
        } catch (SQLException e) {
            // ya estaba cerrada
        }
    }
}
