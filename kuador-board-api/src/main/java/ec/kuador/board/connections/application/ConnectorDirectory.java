package ec.kuador.board.connections.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * El directorio de conectores ({@code connections/directory.yaml}): sistemas conocidos que se conectan eligiéndolos de
 * una lista. Los que no dejan que el tablero se registre solo usan una aplicación registrada una vez para toda la
 * plataforma ({@code board.connectors.<proveedor>.client-id} y {@code client-secret}); sin ella, quien conecta pega la suya.
 */
@Component
public class ConnectorDirectory {

    /** Un conector del directorio. {@code provider}: de quién es la aplicación registrada que usa (nulo si se registra solo). */
    public record Connector(String key, String name, String description, String category, String url, boolean selfRegisters, String provider,
            String scope, Map<String, String> params) {
        public Connector {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /** Lo que ve quien conecta. {@code ready}: se conecta solo con iniciar sesión, sin pegar ningún identificador. */
    public record Entry(String key, String name, String description, String category, String url, boolean ready) {}

    record Client(String id, String secret) {}

    private record File(List<Connector> connectors) {}

    private final List<Connector> connectors;
    private final Environment env;

    ConnectorDirectory(Environment env) {
        this.env = env;
        YAMLMapper yaml = YAMLMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        try (InputStream in = new ClassPathResource("connections/directory.yaml").getInputStream()) {
            this.connectors = List.copyOf(yaml.readValue(in, File.class).connectors());
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el directorio de conectores", e);
        }
        for (Connector c : connectors) {
            if (c.key() == null || c.name() == null || c.url() == null || !c.url().startsWith("https://") || (!c.selfRegisters() && c.provider() == null)) {
                throw new IllegalStateException("Conector mal definido en connections/directory.yaml: " + c.key());
            }
        }
    }

    public List<Entry> entries() {
        return connectors.stream().map(c -> new Entry(c.key(), c.name(), c.description(), c.category(), c.url(), c.selfRegisters() || client(c).isPresent())).toList();
    }

    Optional<Connector> find(String key) {
        return key == null || key.isBlank() ? Optional.empty() : connectors.stream().filter(c -> c.key().equalsIgnoreCase(key.strip())).findFirst();
    }

    /** La aplicación de la plataforma registrada con el proveedor del conector, si se configuró. */
    Optional<Client> client(Connector c) {
        if (c.provider() == null) return Optional.empty();
        String base = "board.connectors." + c.provider().toLowerCase(Locale.ROOT);
        String id = env.getProperty(base + ".client-id", "");
        return id.isBlank() ? Optional.empty() : Optional.of(new Client(id.strip(), env.getProperty(base + ".client-secret", "").strip()));
    }
}
