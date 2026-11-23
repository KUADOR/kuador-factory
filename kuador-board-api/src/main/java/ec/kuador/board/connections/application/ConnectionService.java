package ec.kuador.board.connections.application;

import ec.kuador.board.connections.ConnectionsApi;
import ec.kuador.board.connections.domain.Connection;
import ec.kuador.board.connections.domain.ConnectionStore;
import ec.kuador.board.connections.domain.McpClient;
import ec.kuador.board.shared.crypto.SecretBox;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.net.SafeUrls;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conexiones del negocio del espacio: se registran (el servidor se comprueba listando sus herramientas), se asignan a
 * tableros y roles, y los agentes las usan a través del tablero. Regla fija: una herramienta que solo lee se ofrece; una
 * que escribe, solo si quien administra la habilitó a propósito.
 */
@Service
public class ConnectionService implements ConnectionsApi {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);
    private static final int MAX_CONNECTIONS = 20;
    /** Lo que vuelve al modelo: una respuesta enorme se paga en cada vuelta. */
    private static final int MAX_RESULT = 40_000;
    private static final Pattern ROLE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final Pattern TOOL_NAME = Pattern.compile("[^a-zA-Z0-9_-]");

    private final ConnectionStore store;
    private final McpClient mcp;
    private final SecretBox secrets;
    private final Clock clock;
    private final boolean allowPrivate;

    ConnectionService(ConnectionStore store, McpClient mcp, SecretBox secrets, Clock clock,
            @Value("${board.connections.allow-private:false}") boolean allowPrivate) {
        this.store = store;
        this.mcp = mcp;
        this.secrets = secrets;
        this.clock = clock;
        this.allowPrivate = allowPrivate;
    }

    /** Lo que ve quien administra: nunca la credencial, solo si hay una. */
    public record View(String key, String name, String url, String header, boolean hasSecret, List<ToolView> tools, java.time.Instant checkedAt,
            List<BoardUse> boards) {}

    public record ToolView(String name, String description, boolean readOnly, boolean allowed) {}

    public record BoardUse(String projectKey, List<String> roles) {}

    @Transactional(readOnly = true)
    public List<View> list() {
        UUID ws = TenantContext.require();
        List<ConnectionStore.Use> uses = store.uses(ws);
        return store.all(ws).stream().map(c -> view(c, uses)).toList();
    }

    /**
     * Crea o actualiza una conexión. {@code secret} nulo conserva la credencial guardada; vacío la quita. Antes de
     * guardar se comprueba con el servidor: si no responde o rechaza la credencial, no se guarda.
     */
    @Transactional
    public View save(String key, String name, String url, String header, String secret) {
        UUID ws = TenantContext.require();
        String k = key == null ? "" : key.strip().toUpperCase(Locale.ROOT);
        Connection previous = store.find(ws, k).orElse(null);
        if (previous == null && store.all(ws).size() >= MAX_CONNECTIONS) throw new ConflictException("Hasta " + MAX_CONNECTIONS + " conexiones por espacio.");
        String stored = secret == null ? (previous == null ? null : previous.secret()) : secret.isBlank() ? null : secrets.encrypt(secret.strip());
        Connection draft = new Connection(k, name, url, header, stored, previous == null ? List.of() : previous.tools(),
                previous == null ? Set.of() : previous.allowedWrites(), null);
        SafeUrls.requirePublicHttps(draft.url(), allowPrivate);
        Connection checked = draft.withTools(listTools(draft), clock.instant());
        store.save(ws, checked);
        return view(checked, store.uses(ws));
    }

    /** Vuelve a pedir las herramientas al servidor (cambiaron, o se quiere comprobar que sigue respondiendo). */
    @Transactional
    public View refresh(String key) {
        UUID ws = TenantContext.require();
        Connection c = require(ws, key);
        Connection checked = c.withTools(listTools(c), clock.instant());
        store.save(ws, checked);
        return view(checked, store.uses(ws));
    }

    /** Qué herramientas que escriben pueden usar los agentes sin que una persona las ejecute. */
    @Transactional
    public View allowWrites(String key, List<String> tools) {
        UUID ws = TenantContext.require();
        Connection updated = require(ws, key).withAllowedWrites(new LinkedHashSet<>(tools == null ? List.of() : tools));
        store.save(ws, updated);
        return view(updated, store.uses(ws));
    }

    @Transactional
    public void delete(String key) {
        UUID ws = TenantContext.require();
        store.delete(ws, require(ws, key).key());
    }

    /** Las conexiones que usa un tablero y para qué roles. */
    @Transactional(readOnly = true)
    public List<ConnectionStore.Use> boardUses(String projectKey) {
        return store.usesOf(TenantContext.require(), projectKey.toUpperCase(Locale.ROOT));
    }

    /** Reemplaza las conexiones de un tablero. {@code roles} vacío = todos los roles del tablero. */
    @Transactional
    public List<ConnectionStore.Use> setBoardUses(String projectKey, List<BoardUseRequest> uses) {
        UUID ws = TenantContext.require();
        String project = projectKey.toUpperCase(Locale.ROOT);
        Map<String, ConnectionStore.Use> clean = new LinkedHashMap<>();
        for (BoardUseRequest u : uses == null ? List.<BoardUseRequest>of() : uses) {
            Connection c = require(ws, u.connection());
            List<String> roles = u.roles() == null ? List.of() : u.roles().stream().map(r -> r.strip().toUpperCase(Locale.ROOT)).distinct().toList();
            for (String r : roles) if (!ROLE.matcher(r).matches()) throw new DomainException("Rol no válido: " + r + ".");
            clean.put(c.key(), new ConnectionStore.Use(c.key(), project, roles));
        }
        store.saveUses(ws, project, List.copyOf(clean.values()));
        return store.usesOf(ws, project);
    }

    public record BoardUseRequest(String connection, List<String> roles) {}

    /** En qué tableros se usa una conexión (reemplaza los anteriores). {@code roles} vacío = todos los roles del tablero. */
    @Transactional
    public View setBoards(String key, List<BoardUse> boards) {
        UUID ws = TenantContext.require();
        Connection c = require(ws, key);
        Map<String, List<String>> wanted = new LinkedHashMap<>();
        for (BoardUse b : boards == null ? List.<BoardUse>of() : boards) {
            if (b.projectKey() == null || b.projectKey().isBlank()) continue;
            List<String> roles = b.roles() == null ? List.of() : b.roles().stream().map(r -> r.strip().toUpperCase(Locale.ROOT)).distinct().toList();
            for (String r : roles) if (!ROLE.matcher(r).matches()) throw new DomainException("Rol no válido: " + r + ".");
            wanted.put(b.projectKey().strip().toUpperCase(Locale.ROOT), roles);
        }
        Set<String> touched = new LinkedHashSet<>(wanted.keySet());
        store.uses(ws).stream().filter(u -> u.connection().equals(c.key())).forEach(u -> touched.add(u.projectKey()));
        for (String project : touched) {
            List<ConnectionStore.Use> rest = new ArrayList<>(store.usesOf(ws, project).stream().filter(u -> !u.connection().equals(c.key())).toList());
            if (wanted.containsKey(project)) rest.add(new ConnectionStore.Use(c.key(), project, wanted.get(project)));
            store.saveUses(ws, project, rest);
        }
        return view(c, store.uses(ws));
    }

    // ---- ConnectionsApi: lo que usan los agentes ----

    @Override
    @Transactional(readOnly = true)
    public List<Tool> toolsFor(String projectKey, String roleKey) {
        UUID ws = TenantContext.require();
        List<Tool> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ConnectionStore.Use use : store.usesOf(ws, projectKey.toUpperCase(Locale.ROOT))) {
            if (!use.roles().isEmpty() && (roleKey == null || !use.roles().contains(roleKey))) continue;
            Connection c = store.find(ws, use.connection()).orElse(null);
            if (c == null) continue;
            for (Connection.ToolDef t : c.tools()) {
                if (!c.offers(t)) continue;
                String name = modelName(c.key(), t.name());
                if (seen.add(name)) out.add(new Tool(name, c.key(), c.name(), t.name(), t.description(), t.schema(), t.readOnly()));
            }
        }
        return out;
    }

    @Override
    public String call(String projectKey, String roleKey, String toolName, Map<String, Object> arguments, UUID actorId, String issueKey) {
        UUID ws = TenantContext.require();
        // Se vuelve a comprobar en cada llamada: la asignación o el permiso pudieron cambiar mientras el agente trabajaba.
        Tool tool = toolsFor(projectKey, roleKey).stream().filter(t -> t.name().equals(toolName)).findFirst()
                .orElseThrow(() -> new DomainException("Esa herramienta no está disponible para tu rol en este tablero."));
        Connection c = store.find(ws, tool.connection()).orElseThrow(() -> new NotFoundException("La conexión ya no existe."));
        String secret = c.secret() == null ? null : secrets.decrypt(c.secret());
        log.info("Conexión {} · {} ({}) por {} en {} [{}]", c.key(), tool.tool(), tool.readOnly() ? "lee" : "escribe", roleKey, issueKey, actorId);
        String result = mcp.callTool(c.url(), c.header(), secret, tool.tool(), arguments);
        return result.length() > MAX_RESULT ? result.substring(0, MAX_RESULT) + "\n… (respuesta recortada: pide menos datos o filtra)" : result;
    }

    /** El nombre con el que el modelo ve la herramienta: conexión y herramienta, con los caracteres que todos aceptan. */
    static String modelName(String connection, String tool) {
        String name = connection.toLowerCase(Locale.ROOT) + "__" + TOOL_NAME.matcher(tool).replaceAll("_");
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    private List<Connection.ToolDef> listTools(Connection c) {
        try {
            return mcp.listTools(c.url(), c.header(), c.secret() == null ? null : secrets.decrypt(c.secret()));
        } catch (McpClient.McpException e) {
            throw new DomainException("No se pudo conectar con «" + c.name() + "»: " + e.getMessage());
        }
    }

    private Connection require(UUID ws, String key) {
        return store.find(ws, key == null ? "" : key.strip().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NotFoundException("No existe la conexión " + key + "."));
    }

    private static View view(Connection c, List<ConnectionStore.Use> uses) {
        return new View(c.key(), c.name(), c.url(), c.header(), c.secret() != null,
                c.tools().stream().map(t -> new ToolView(t.name(), t.description(), t.readOnly(), c.offers(t))).toList(), c.checkedAt(),
                uses.stream().filter(u -> u.connection().equals(c.key())).map(u -> new BoardUse(u.projectKey(), u.roles())).toList());
    }
}
