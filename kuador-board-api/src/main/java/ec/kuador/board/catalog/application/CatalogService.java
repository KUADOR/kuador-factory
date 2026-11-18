package ec.kuador.board.catalog.application;

import ec.kuador.board.catalog.AgentPolicy;
import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.CatalogUsage;
import ec.kuador.board.catalog.ComponentTemplate;
import ec.kuador.board.catalog.McpServer;
import ec.kuador.board.catalog.Methodology;
import ec.kuador.board.catalog.WorkflowTemplate;
import ec.kuador.board.catalog.domain.CatalogKeys;
import ec.kuador.board.catalog.domain.CatalogRepository;
import ec.kuador.board.catalog.domain.CatalogRules;
import ec.kuador.board.catalog.domain.CatalogType;
import ec.kuador.board.catalog.domain.FactoryCatalog;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.function.ToLongFunction;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Catálogo del espacio actual: lectura para los demás módulos y administración para personas. */
@Service
public class CatalogService implements CatalogApi {

    private final CatalogRepository entries;
    private final ec.kuador.board.catalog.domain.PlatformCatalogStore platform;
    private final FactoryCatalog factory;
    // Quienes usan el catálogo también dependen de él: se resuelven recién al borrar (evita el ciclo).
    private final ObjectProvider<CatalogUsage> usages;

    CatalogService(CatalogRepository entries, FactoryCatalog factory, ObjectProvider<CatalogUsage> usages,
            ec.kuador.board.catalog.domain.PlatformCatalogStore platform) {
        this.platform = platform;
        this.entries = entries;
        this.factory = factory;
        this.usages = usages;
    }

    // ---- CatalogApi ----

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowTemplate> workflowTemplates() {
        return entries.list(CatalogType.WORKFLOW_TEMPLATE, WorkflowTemplate.class);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkflowTemplate> workflowTemplate(String key) {
        return entries.find(CatalogType.WORKFLOW_TEMPLATE, key, WorkflowTemplate.class);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Methodology> methodologies() {
        return entries.list(CatalogType.METHODOLOGY, Methodology.class);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Methodology> methodology(String key) {
        return key == null ? Optional.empty() : entries.find(CatalogType.METHODOLOGY, key, Methodology.class);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentRole> roles() {
        return entries.list(CatalogType.AGENT_ROLE, AgentRole.class);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentRole> role(String key) {
        return key == null ? Optional.empty() : entries.find(CatalogType.AGENT_ROLE, key, AgentRole.class);
    }

    @Override
    @Transactional(readOnly = true)
    public List<McpServer> mcpServers() {
        return entries.list(CatalogType.MCP_SERVER, McpServer.class);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComponentTemplate> componentTemplates() {
        return entries.list(CatalogType.COMPONENT_TEMPLATE, ComponentTemplate.class);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ComponentTemplate> componentTemplate(String key) {
        return entries.find(CatalogType.COMPONENT_TEMPLATE, key, ComponentTemplate.class);
    }

    @Override
    @Transactional(readOnly = true)
    public AgentPolicy agentPolicy() {
        AgentPolicy factoryPolicy = factory.agentPolicies().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("El catálogo de fábrica no tiene reglas de agentes."));
        return entries.find(CatalogType.AGENT_POLICY, AgentPolicy.KEY, AgentPolicy.class).map(p -> p.orElse(factoryPolicy)).orElse(factoryPolicy);
    }

    @Override
    @Transactional
    public List<AgentSeed> provisionDefaults() {
        for (CatalogType type : CatalogType.values()) {
            for (Object entry : factoryEntries(type)) {
                insert(type, entry, true);
            }
        }
        return factory.agentSeeds();
    }

    @Override
    public int factoryVersion() {
        return factory.version();
    }

    @Override
    @Transactional
    public int receiveFactoryAdditions(int fromVersion) {
        int added = 0;
        for (var addition : factory.addedAfter(fromVersion)) {
            CatalogType type = addition.getKey();
            if (entries.find(type, addition.getValue(), type.recordType()).isPresent()) continue;
            // Una entrada que la plataforma quitó después de anunciarla ya no se reparte.
            Optional<Object> entry = factory.entry(type, addition.getValue());
            if (entry.isEmpty()) continue;
            insert(type, entry.get(), true);
            added++;
        }
        return added;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> defaultTeam(String templateKey) {
        List<String> own = workflowTemplate(templateKey).map(WorkflowTemplate::teamRoles).orElse(List.of());
        if (!own.isEmpty()) return own;
        return factory.workflowTemplates().stream().filter(t -> t.key().equals(templateKey)).findFirst().map(WorkflowTemplate::teamRoles).orElse(List.of());
    }

    @Override
    public Optional<AgentSeed> agentSeed(String roleKey) {
        return factory.agentSeeds().stream().filter(s -> s.roleKey().equals(roleKey)).findFirst();
    }

    @Override
    @Transactional
    public Optional<StarterBoard> choosePackage(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        Optional<ec.kuador.board.catalog.domain.PlatformCatalogStore.Package> p = platform.pkg(code.strip().toUpperCase())
                .filter(ec.kuador.board.catalog.domain.PlatformCatalogStore.Package::active);
        if (p.isEmpty()) return Optional.empty();
        platform.choosePackage(ec.kuador.board.shared.tenant.TenantContext.require(), p.get().code(), java.time.Instant.now());
        return p.filter(x -> x.starterTemplate() != null).map(x -> new StarterBoard(x.starterKey(), x.starterName(), x.starterTemplate()));
    }

    /** Paquete del espacio actual (sus plantillas se recomiendan al crear un tablero). */
    @Transactional(readOnly = true)
    public Optional<ec.kuador.board.catalog.domain.PlatformCatalogStore.Package> currentPackage() {
        return platform.packageOf(ec.kuador.board.shared.tenant.TenantContext.require()).flatMap(platform::pkg);
    }

    @Override
    @Transactional
    @SuppressWarnings({"unchecked", "rawtypes"})
    public int applyFactoryUpdates(List<ec.kuador.board.catalog.FactoryCatalogPublished.Update> updates) {
        int changed = 0;
        for (var u : updates) {
            CatalogType type = CatalogType.valueOf(u.type());
            Optional<?> current = entries.find(type, u.key(), type.recordType());
            if (current.isEmpty() || !builtIn(current.get())) continue;
            // Si el equipo la modificó, se respeta su versión.
            if (!entries.payloadOf(current.get()).equals(u.previousPayload())) continue;
            Optional<Object> fresh = factory.entry(type, u.key());
            if (fresh.isEmpty()) continue;
            Class recordType = type.recordType();
            entries.save(type, u.key(), fresh.get(), true, versionOf(current.get()), recordType);
            changed++;
        }
        return changed;
    }

    // ---- Administración (personas) ----

    @Transactional(readOnly = true)
    public List<?> list(CatalogType type) {
        List<?> own = entries.list(type, type.recordType());
        // Un espacio que todavía no recibió las reglas de agentes ve (y edita) las de fábrica.
        if (type == CatalogType.AGENT_POLICY && own.isEmpty()) return List.of(agentPolicy());
        return own;
    }

    @Transactional(readOnly = true)
    public Object get(CatalogType type, String key) {
        if (type == CatalogType.AGENT_POLICY && AgentPolicy.KEY.equals(key)) return agentPolicy();
        return entries.find(type, key, type.recordType())
                .orElseThrow(() -> new NotFoundException("No existe " + key + " en el catálogo."));
    }

    /**
     * Crea o actualiza una entrada. Al actualizar, la versión de la entrada recibida debe ser la
     * vigente: si otra persona guardó antes, se rechaza en vez de pisar su cambio.
     */
    @Transactional
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object save(CatalogType type, String key, Object entry) {
        if (!key.equals(CatalogKeys.of(entry))) {
            throw new DomainException("La clave de la URL (" + key + ") no coincide con la de la entrada (" + CatalogKeys.of(entry) + ").");
        }
        CatalogRules.validate(type, key, entry);
        Optional<?> existing = entries.find(type, key, type.recordType());
        Class recordType = type.recordType();
        if (existing.isEmpty()) {
            // Las reglas de agentes vienen de fábrica aunque el espacio las guarde por primera vez.
            return entries.save(type, key, entry, type == CatalogType.AGENT_POLICY, null, recordType);
        }
        return entries.save(type, key, entry, builtIn(existing.get()), versionOf(entry), recordType);
    }

    @Transactional
    public void delete(CatalogType type, String key) {
        get(type, key);
        if (type == CatalogType.AGENT_POLICY) {
            throw new ConflictException("Las reglas de agentes no se borran: edítalas o restáuralas a los valores de fábrica.");
        }
        long inUse = switch (type) {
            case METHODOLOGY -> sum(u -> u.projectsUsingMethodology(key));
            case AGENT_ROLE -> sum(u -> u.membersWithRole(key));
            default -> 0;
        };
        if (inUse > 0) {
            String what = type == CatalogType.METHODOLOGY ? " proyecto(s) la usan." : " miembro(s) tienen este rol.";
            throw new ConflictException("No se puede borrar " + key + ": " + inUse + what);
        }
        entries.delete(type, key);
    }

    // ---- Historial ----

    @Transactional(readOnly = true)
    public List<ec.kuador.board.catalog.domain.CatalogRepository.Revision> revisions(CatalogType type, String key) {
        get(type, key);
        return entries.revisions(type, key);
    }

    @Transactional(readOnly = true)
    public Object revision(CatalogType type, String key, java.util.UUID id) {
        return entries.revision(type, key, id, type.recordType())
                .orElseThrow(() -> new NotFoundException("No existe esa versión de " + key + "."));
    }

    /** Vuelve a una versión anterior: se guarda como una versión nueva (la actual queda en el historial). */
    @Transactional
    public Object restoreRevision(CatalogType type, String key, java.util.UUID id) {
        return save(type, key, revision(type, key, id));
    }

    /** Vuelve una entrada de fábrica a sus valores originales (crea una versión nueva). */
    @Transactional
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object restore(CatalogType type, String key) {
        Object original = factory.entry(type, key)
                .orElseThrow(() -> new NotFoundException(key + " no viene de fábrica: no hay valores para restaurar."));
        Optional<?> existing = entries.find(type, key, type.recordType());
        Class recordType = type.recordType();
        return entries.save(type, key, original, true, existing.map(CatalogService::versionOf).orElse(null), recordType);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void insert(CatalogType type, Object entry, boolean builtIn) {
        String key = CatalogKeys.of(entry);
        CatalogRules.validate(type, key, entry);
        Class recordType = type.recordType();
        entries.save(type, key, entry, builtIn, null, recordType);
    }

    private List<?> factoryEntries(CatalogType type) {
        return switch (type) {
            case WORKFLOW_TEMPLATE -> factory.workflowTemplates();
            case METHODOLOGY -> factory.methodologies();
            case AGENT_ROLE -> factory.roles();
            case MCP_SERVER -> factory.mcpServers();
            case COMPONENT_TEMPLATE -> factory.componentTemplates();
            case AGENT_POLICY -> factory.agentPolicies();
        };
    }

    private long sum(ToLongFunction<CatalogUsage> count) {
        return usages.orderedStream().mapToLong(count).sum();
    }

    private static boolean builtIn(Object entry) {
        return switch (entry) {
            case WorkflowTemplate t -> t.builtIn();
            case Methodology m -> m.builtIn();
            case AgentRole r -> r.builtIn();
            case McpServer s -> s.builtIn();
            case ComponentTemplate c -> c.builtIn();
            case AgentPolicy p -> p.builtIn();
            default -> false;
        };
    }

    private static long versionOf(Object entry) {
        return switch (entry) {
            case WorkflowTemplate t -> t.version();
            case Methodology m -> m.version();
            case AgentRole r -> r.version();
            case McpServer s -> s.version();
            case ComponentTemplate c -> c.version();
            case AgentPolicy p -> p.version();
            default -> 0;
        };
    }
}
