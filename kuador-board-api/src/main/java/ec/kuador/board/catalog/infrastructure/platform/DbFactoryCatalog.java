package ec.kuador.board.catalog.infrastructure.platform;

import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.ComponentTemplate;
import ec.kuador.board.catalog.McpServer;
import ec.kuador.board.catalog.Methodology;
import ec.kuador.board.catalog.WorkflowTemplate;
import ec.kuador.board.catalog.domain.CatalogKeys;
import ec.kuador.board.catalog.domain.CatalogType;
import ec.kuador.board.catalog.domain.FactoryCatalog;
import ec.kuador.board.catalog.domain.PlatformCatalogStore;
import ec.kuador.board.catalog.infrastructure.CatalogJson;
import ec.kuador.board.catalog.infrastructure.defaults.YamlFactoryCatalog;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El catálogo de fábrica que reciben los espacios: lo publicado del catálogo de la plataforma (los borradores no
 * llegan a nadie). La primera vez se siembra desde {@code catalog/defaults.yaml}. Se lee una vez y se recarga al
 * publicar.
 */
@Component
public class DbFactoryCatalog implements FactoryCatalog {

    private static final Logger log = LoggerFactory.getLogger(DbFactoryCatalog.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PlatformCatalogStore store;
    private final Clock clock;
    private volatile Snapshot snapshot;

    record Snapshot(Map<CatalogType, List<Object>> entries, List<CatalogApi.AgentSeed> seeds, List<PlatformCatalogStore.Release> releases) {}

    DbFactoryCatalog(PlatformCatalogStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Tras publicar: la próxima lectura trae lo nuevo. */
    @Override
    public void reload() {
        snapshot = null;
    }

    private Snapshot snapshot() {
        Snapshot s = snapshot;
        if (s != null) return s;
        synchronized (this) {
            if (snapshot == null) {
                seedIfEmpty();
                snapshot = load();
            }
            return snapshot;
        }
    }

    /** Primera vez: copia la semilla (YAML) como versión publicada, con sus versiones. */
    private void seedIfEmpty() {
        if (!store.isEmpty()) return;
        YamlFactoryCatalog seed = new YamlFactoryCatalog();
        Instant now = clock.instant();
        Map<String, CatalogApi.AgentSeed> agents = new java.util.HashMap<>();
        seed.agentSeeds().forEach(a -> agents.put(a.roleKey(), a));
        for (CatalogType type : CatalogType.values()) {
            for (Object e : seed.entries(type)) {
                String key = CatalogKeys.of(e);
                String payload = CatalogJson.payload(e);
                String extra = null;
                if (type == CatalogType.AGENT_ROLE && agents.containsKey(key)) {
                    CatalogApi.AgentSeed a = agents.get(key);
                    extra = JSON.createObjectNode().put("memberName", a.memberName()).put("color", a.color()).toString();
                }
                store.save(new PlatformCatalogStore.Row(type, key, payload, payload, extra), now);
            }
        }
        Map<Integer, List<Map.Entry<CatalogType, String>>> releases = seed.releasesByVersion();
        for (int v = 1; v <= seed.version(); v++) {
            store.saveRelease(new PlatformCatalogStore.Release(v, releases.getOrDefault(v, List.of()), List.of(), now));
        }
        log.info("Catálogo de la plataforma sembrado desde catalog/defaults.yaml (versión {})", seed.version());
    }

    private Snapshot load() {
        Map<CatalogType, List<Object>> entries = new java.util.EnumMap<>(CatalogType.class);
        for (CatalogType t : CatalogType.values()) entries.put(t, new ArrayList<>());
        List<CatalogApi.AgentSeed> seeds = new ArrayList<>();
        for (PlatformCatalogStore.Row row : store.rows()) {
            if (row.published() == null) continue; // borrador nunca publicado
            Object entry = parse(row.type(), row.key(), row.published());
            entries.get(row.type()).add(entry);
            if (row.type() == CatalogType.AGENT_ROLE) {
                AgentRole role = (AgentRole) entry;
                JsonNode extra = row.extra() == null ? JSON.createObjectNode() : JSON.readTree(row.extra());
                seeds.add(new CatalogApi.AgentSeed(role.key(), extra.path("memberName").asString("Agente " + role.name()),
                        extra.path("color").asString("#8fb4ff")));
            }
        }
        return new Snapshot(entries, seeds, store.releases());
    }

    /** Lee una entrada (borrador o publicada) como su registro. */
    public static Object parse(CatalogType type, String key, String payload) {
        UUID id = UUID.nameUUIDFromBytes((type.name() + ":" + key).getBytes(StandardCharsets.UTF_8));
        return CatalogJson.read(payload, id, true, 0, type.recordType());
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> list(CatalogType type) {
        return (List<T>) List.copyOf(snapshot().entries().get(type));
    }

    @Override
    public List<WorkflowTemplate> workflowTemplates() {
        return list(CatalogType.WORKFLOW_TEMPLATE);
    }

    @Override
    public List<Methodology> methodologies() {
        return list(CatalogType.METHODOLOGY);
    }

    @Override
    public List<AgentRole> roles() {
        return list(CatalogType.AGENT_ROLE);
    }

    @Override
    public List<McpServer> mcpServers() {
        return list(CatalogType.MCP_SERVER);
    }

    @Override
    public List<ComponentTemplate> componentTemplates() {
        return list(CatalogType.COMPONENT_TEMPLATE);
    }

    @Override
    public List<CatalogApi.AgentSeed> agentSeeds() {
        return snapshot().seeds();
    }

    @Override
    public int version() {
        return Math.max(1, snapshot().releases().stream().mapToInt(PlatformCatalogStore.Release::version).max().orElse(1));
    }

    @Override
    public List<Map.Entry<CatalogType, String>> addedAfter(int version) {
        return snapshot().releases().stream().filter(r -> r.version() > version).flatMap(r -> r.added().stream()).toList();
    }
}
