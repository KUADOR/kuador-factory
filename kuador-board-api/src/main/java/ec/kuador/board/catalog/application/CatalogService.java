package ec.kuador.board.catalog.application;

import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.CatalogUsage;
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
    private final FactoryCatalog factory;
    // Quienes usan el catálogo también dependen de él: se resuelven recién al borrar (evita el ciclo).
    private final ObjectProvider<CatalogUsage> usages;

    CatalogService(CatalogRepository entries, FactoryCatalog factory, ObjectProvider<CatalogUsage> usages) {
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
            Object entry = factory.entry(type, addition.getValue()).orElseThrow();
            insert(type, entry, true);
            added++;
        }
        return added;
    }

    // ---- Administración (personas) ----

    @Transactional(readOnly = true)
    public List<?> list(CatalogType type) {
        return entries.list(type, type.recordType());
    }

    @Transactional(readOnly = true)
    public Object get(CatalogType type, String key) {
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
            return entries.save(type, key, entry, false, null, recordType);
        }
        return entries.save(type, key, entry, builtIn(existing.get()), versionOf(entry), recordType);
    }

    @Transactional
    public void delete(CatalogType type, String key) {
        get(type, key);
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
            default -> false;
        };
    }

    private static long versionOf(Object entry) {
        return switch (entry) {
            case WorkflowTemplate t -> t.version();
            case Methodology m -> m.version();
            case AgentRole r -> r.version();
            case McpServer s -> s.version();
            default -> 0;
        };
    }
}
