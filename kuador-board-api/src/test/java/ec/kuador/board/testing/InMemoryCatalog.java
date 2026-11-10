package ec.kuador.board.testing;

import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.CatalogApi;
import ec.kuador.board.catalog.McpServer;
import ec.kuador.board.catalog.Methodology;
import ec.kuador.board.catalog.WorkflowTemplate;
import ec.kuador.board.catalog.infrastructure.defaults.YamlFactoryCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Catálogo en memoria con los valores de fábrica reales (catalog/defaults.yaml). */
public class InMemoryCatalog implements CatalogApi {

    public static final YamlFactoryCatalog FACTORY = new YamlFactoryCatalog();

    public final List<WorkflowTemplate> templates = new ArrayList<>(FACTORY.workflowTemplates());
    public final List<Methodology> methodologies = new ArrayList<>(FACTORY.methodologies());
    public final List<AgentRole> roles = new ArrayList<>(FACTORY.roles());

    public static Methodology factoryMethodology(String key) {
        return FACTORY.methodologies().stream().filter(m -> m.key().equals(key)).findFirst().orElseThrow();
    }

    public static WorkflowTemplate factoryTemplate(String key) {
        return FACTORY.workflowTemplates().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow();
    }

    @Override
    public List<WorkflowTemplate> workflowTemplates() {
        return templates;
    }

    @Override
    public Optional<WorkflowTemplate> workflowTemplate(String key) {
        return templates.stream().filter(t -> t.key().equals(key)).findFirst();
    }

    @Override
    public List<Methodology> methodologies() {
        return methodologies;
    }

    @Override
    public Optional<Methodology> methodology(String key) {
        return methodologies.stream().filter(m -> m.key().equals(key)).findFirst();
    }

    @Override
    public List<AgentRole> roles() {
        return roles;
    }

    @Override
    public Optional<AgentRole> role(String key) {
        return roles.stream().filter(r -> r.key().equals(key)).findFirst();
    }

    @Override
    public List<McpServer> mcpServers() {
        return FACTORY.mcpServers();
    }

    @Override
    public List<AgentSeed> provisionDefaults() {
        return FACTORY.agentSeeds();
    }

    @Override
    public int factoryVersion() {
        return FACTORY.version();
    }

    @Override
    public int receiveFactoryAdditions(int fromVersion) {
        return 0;
    }
}
