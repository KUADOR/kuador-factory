package ec.kuador.board.catalog.infrastructure.defaults;

import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.ComponentTemplate;
import ec.kuador.board.catalog.McpServer;
import ec.kuador.board.catalog.Methodology;
import ec.kuador.board.catalog.WorkflowTemplate;
import ec.kuador.board.catalog.domain.CatalogKeys;
import ec.kuador.board.catalog.domain.CatalogRules;
import ec.kuador.board.catalog.domain.CatalogType;
import ec.kuador.board.catalog.domain.FactoryCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Lee {@code catalog/defaults.yaml} y lo valida con las mismas reglas que una edición. Es la semilla del catálogo de
 * la plataforma: se copia a la base la primera vez; desde ahí lo edita quien administra la plataforma.
 */
public class YamlFactoryCatalog implements FactoryCatalog {

    static final String RESOURCE = "catalog/defaults.yaml";

    /** Un rol de fábrica trae además el agente que nace con cada espacio. */
    record RoleSeed(String key, String name, String description, String instructions, List<String> tools,
            AgentRole.Automation automation, String memberName, String color) {}

    /** Lo que se agregó al catálogo de fábrica en una versión. */
    record Release(int version, List<Addition> added) {}

    record Addition(CatalogType type, String key) {}

    record DefaultsFile(int version, List<Release> releases, List<WorkflowTemplate> workflowTemplates, List<Methodology> methodologies,
            List<RoleSeed> roles, List<McpServer> mcpServers, List<ComponentTemplate> componentTemplates) {}

    private final DefaultsFile file;

    public YamlFactoryCatalog() {
        this(RESOURCE);
    }

    YamlFactoryCatalog(String resource) {
        YAMLMapper yaml = YAMLMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            this.file = yaml.readValue(in, DefaultsFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + resource, e);
        }
        workflowTemplates().forEach(e -> CatalogRules.validate(CatalogType.WORKFLOW_TEMPLATE, CatalogKeys.of(e), e));
        methodologies().forEach(e -> CatalogRules.validate(CatalogType.METHODOLOGY, CatalogKeys.of(e), e));
        roles().forEach(e -> CatalogRules.validate(CatalogType.AGENT_ROLE, CatalogKeys.of(e), e));
        mcpServers().forEach(e -> CatalogRules.validate(CatalogType.MCP_SERVER, CatalogKeys.of(e), e));
        componentTemplates().forEach(e -> CatalogRules.validate(CatalogType.COMPONENT_TEMPLATE, CatalogKeys.of(e), e));
        // Cada entrada anunciada en una versión tiene que existir en el catálogo.
        for (Release r : releases()) {
            if (r.version() > version()) throw new IllegalStateException("La versión " + r.version() + " es mayor que la del catálogo (" + version() + ").");
            for (Addition a : r.added()) {
                if (entry(a.type(), a.key()).isEmpty()) {
                    throw new IllegalStateException("La versión " + r.version() + " agrega " + a.type() + " " + a.key() + ", que no está en el catálogo.");
                }
            }
        }
    }

    /** Versiones de la semilla: número y lo que agregó cada una. */
    public java.util.Map<Integer, List<java.util.Map.Entry<CatalogType, String>>> releasesByVersion() {
        java.util.Map<Integer, List<java.util.Map.Entry<CatalogType, String>>> out = new java.util.TreeMap<>();
        for (Release r : releases()) out.put(r.version(), r.added().stream().map(a -> java.util.Map.entry(a.type(), a.key())).toList());
        return out;
    }

    private List<Release> releases() {
        return file.releases() == null ? List.of() : file.releases();
    }

    @Override
    public int version() {
        return Math.max(1, file.version());
    }

    @Override
    public List<java.util.Map.Entry<CatalogType, String>> addedAfter(int version) {
        return releases().stream().filter(r -> r.version() > version)
                .flatMap(r -> r.added().stream()).map(a -> java.util.Map.entry(a.type(), a.key())).toList();
    }

    @Override
    public List<WorkflowTemplate> workflowTemplates() {
        return file.workflowTemplates();
    }

    @Override
    public List<Methodology> methodologies() {
        return file.methodologies();
    }

    @Override
    public List<AgentRole> roles() {
        return file.roles().stream()
                .map(r -> new AgentRole(null, r.key(), r.name(), r.description(), r.instructions(), r.tools(), r.automation(), true, 0))
                .toList();
    }

    @Override
    public List<McpServer> mcpServers() {
        return file.mcpServers();
    }

    @Override
    public List<ComponentTemplate> componentTemplates() {
        return file.componentTemplates() == null ? List.of() : file.componentTemplates();
    }

    @Override
    public List<CatalogApi.AgentSeed> agentSeeds() {
        return file.roles().stream().map(r -> new CatalogApi.AgentSeed(r.key(), r.memberName(), r.color())).toList();
    }
}
