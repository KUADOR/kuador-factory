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
    private final OAuthSessions sessions;
    private final Clock clock;
    private final boolean allowPrivate;
    /** Una renovación a la vez por conexión: algunos servidores cambian el token de renovación en cada uso. */
    private final Map<String, Object> renewing = new java.util.concurrent.ConcurrentHashMap<>();

    ConnectionService(ConnectionStore store, McpClient mcp, SecretBox secrets, OAuthSessions sessions, Clock clock,
            @Value("${board.connections.allow-private:false}") boolean allowPrivate) {
        this.store = store;
        this.mcp = mcp;
        this.secrets = secrets;
        this.sessions = sessions;
        this.clock = clock;
        this.allowPrivate = allowPrivate;
    }

    /**
     * Lo que ve quien administra: nunca la credencial, solo si hay una. {@code auth}: {@code OAUTH} si se entra iniciando
     * sesión, {@code TOKEN} si es con clave (o sin nada); {@code connected}: falso mientras falte iniciar sesión.
     */
    public record View(String key, String name, String url, String header, boolean hasSecret, List<ToolView> tools, java.time.Instant checkedAt,
            List<BoardUse> boards, String auth, boolean connected) {}

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
        // Una conexión con sesión iniciada la conserva al cambiarle el nombre; con otra dirección o una clave, deja de usarla.
        boolean keepsSession = previous != null && previous.oauth() != null && secret == null && url != null && previous.url().equals(url.strip());
        Connection draft = new Connection(k, name, url, keepsSession ? null : header, keepsSession ? null : stored,
                previous == null ? List.of() : previous.tools(), previous == null ? Set.of() : previous.allowedWrites(), null,
                keepsSession ? previous.oauth() : null);
        SafeUrls.requirePublicHttps(draft.url(), allowPrivate);
        Connection checked = withFreshTools(ws, draft);
        store.save(ws, checked);
        return view(checked, store.uses(ws));
    }

    /**
     * Primer paso de una conexión que pide iniciar sesión (OAuth): se guarda sin herramientas y se devuelve la dirección
     * a la que el navegador debe ir. {@code clientId} vacío: el tablero se registra solo en el servidor (o reutiliza el
     * cliente que ya tenía esta conexión). Sirve también para volver a iniciar sesión cuando venció.
     */
    @Transactional
    public String startLogin(String key, String name, String url, String clientId, String clientSecret, String scope, UUID memberId) {
        UUID ws = TenantContext.require();
        String k = key == null ? "" : key.strip().toUpperCase(Locale.ROOT);
        Connection previous = store.find(ws, k).orElse(null);
        if (previous == null && store.all(ws).size() >= MAX_CONNECTIONS) throw new ConflictException("Hasta " + MAX_CONNECTIONS + " conexiones por espacio.");
        boolean same = previous != null && url != null && previous.url().equals(url.strip());
        Connection draft = new Connection(k, name, url, null, null, same ? previous.tools() : List.of(), same ? previous.allowedWrites() : Set.of(),
                same ? previous.checkedAt() : null, null);
        SafeUrls.requirePublicHttps(draft.url(), allowPrivate);
        OAuthSessions.Started started = sessions.start(draft.url(), clientId, clientSecret, scope, same ? previous.oauth() : null, memberId);
        store.save(ws, draft.withOauth(started.session()));
        return started.authorizationUrl();
    }

    /** Segundo paso: el sistema devolvió a la persona con un código. Se canjea, se comprueba el servidor y queda conectada. */
    @Transactional
    public View finishLogin(String state, String code, UUID memberId) {
        UUID ws = TenantContext.require();
        Connection waiting = store.all(ws).stream().filter(c -> sessions.awaits(c.oauth(), state, memberId)).findFirst()
                .orElseThrow(() -> new DomainException("Ese inicio de sesión venció o no corresponde a ninguna conexión: empieza de nuevo."));
        if (code == null || code.isBlank()) throw new DomainException("El sistema no devolvió el código de acceso.");
        Connection logged = waiting.withOauth(sessions.finish(waiting.oauth(), code.strip()));
        // Los tokens se guardan aunque el servidor falle al listar: el código ya se gastó y no se puede canjear dos veces.
        store.save(ws, logged);
        Connection checked = withFreshTools(ws, logged);
        store.save(ws, checked);
        return view(checked, store.uses(ws));
    }

    /** Vuelve a pedir las herramientas al servidor (cambiaron, o se quiere comprobar que sigue respondiendo). */
    @Transactional
    public View refresh(String key) {
        UUID ws = TenantContext.require();
        Connection checked = withFreshTools(ws, require(ws, key));
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
        log.info("Conexión {} · {} ({}) por {} en {} [{}]", c.key(), tool.tool(), tool.readOnly() ? "lee" : "escribe", roleKey, issueKey, actorId);
        String result = withCredential(ws, c, secret -> mcp.callTool(c.url(), c.header(), secret, tool.tool(), arguments));
        return result.length() > MAX_RESULT ? result.substring(0, MAX_RESULT) + "\n… (respuesta recortada: pide menos datos o filtra)" : result;
    }

    /** El nombre con el que el modelo ve la herramienta: conexión y herramienta, con los caracteres que todos aceptan. */
    static String modelName(String connection, String tool) {
        String name = connection.toLowerCase(Locale.ROOT) + "__" + TOOL_NAME.matcher(tool).replaceAll("_");
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    /** La conexión con lo que el servidor ofrece ahora (y con la sesión renovada, si hubo que renovarla). */
    private Connection withFreshTools(UUID ws, Connection c) {
        String[] session = {c.oauth()};
        try {
            List<Connection.ToolDef> tools = withCredential(c, session, secret -> mcp.listTools(c.url(), c.header(), secret));
            return c.withOauth(session[0]).withTools(tools, clock.instant());
        } catch (McpClient.UnauthorizedException e) {
            throw new DomainException("No se pudo conectar con «" + c.name() + "»: " + e.getMessage()
                    + (c.oauth() == null && c.secret() == null ? " Si pide iniciar sesión, conéctalo con esa opción." : ""));
        } catch (McpClient.McpException e) {
            throw new DomainException("No se pudo conectar con «" + c.name() + "»: " + e.getMessage());
        }
    }

    /** Llama con la credencial de una conexión ya guardada; si la sesión se renovó en el camino, la guarda. */
    private <T> T withCredential(UUID ws, Connection c, java.util.function.Function<String, T> work) {
        if (c.oauth() == null) return work.apply(c.secret() == null ? null : secrets.decrypt(c.secret()));
        synchronized (renewing.computeIfAbsent(ws + "/" + c.key(), k -> new Object())) {
            // Otra llamada pudo renovarla mientras esta esperaba: se parte de lo guardado.
            Connection current = store.find(ws, c.key()).filter(x -> x.oauth() != null).orElse(c);
            String[] session = {current.oauth()};
            try {
                return withCredential(current, session, work);
            } finally {
                if (!session[0].equals(current.oauth())) store.save(ws, store.find(ws, c.key()).orElse(current).withOauth(session[0]));
            }
        }
    }

    /**
     * Con clave: la descifra y llama. Con sesión: usa el token vigente (lo renueva si está por vencer) y, si el servidor
     * lo rechaza igual, lo renueva una vez y reintenta. {@code session} sale con la sesión actualizada.
     */
    private <T> T withCredential(Connection c, String[] session, java.util.function.Function<String, T> work) {
        if (session[0] == null) return work.apply(c.secret() == null ? null : secrets.decrypt(c.secret()));
        OAuthSessions.Bearer bearer = sessions.bearer(session[0], false);
        if (bearer.session() != null) session[0] = bearer.session();
        try {
            return work.apply(bearer.token());
        } catch (McpClient.UnauthorizedException e) {
            OAuthSessions.Bearer renewed = sessions.bearer(session[0], true);
            if (renewed.session() != null) session[0] = renewed.session();
            return work.apply(renewed.token());
        }
    }

    private Connection require(UUID ws, String key) {
        return store.find(ws, key == null ? "" : key.strip().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NotFoundException("No existe la conexión " + key + "."));
    }

    private View view(Connection c, List<ConnectionStore.Use> uses) {
        return new View(c.key(), c.name(), c.url(), c.header(), c.secret() != null,
                c.tools().stream().map(t -> new ToolView(t.name(), t.description(), t.readOnly(), c.offers(t))).toList(), c.checkedAt(),
                uses.stream().filter(u -> u.connection().equals(c.key())).map(u -> new BoardUse(u.projectKey(), u.roles())).toList(),
                c.oauth() == null ? "TOKEN" : "OAUTH", c.oauth() == null || sessions.connected(c.oauth()));
    }
}
