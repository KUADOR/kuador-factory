package ec.kuador.board.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.catalog.AgentRole;
import ec.kuador.board.catalog.CatalogUsage;
import ec.kuador.board.catalog.Methodology;
import ec.kuador.board.catalog.domain.CatalogRepository;
import ec.kuador.board.catalog.domain.CatalogType;
import ec.kuador.board.shared.error.ConflictException;
import ec.kuador.board.testing.InMemoryCatalog;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class CatalogServiceTest {

    private final Map<String, Object[]> store = new HashMap<>(); // tipo/clave → {entrada, builtIn, version}
    private long methodologyUsers = 0;
    private CatalogService catalog;

    @BeforeEach
    void setUp() {
        CatalogRepository repo = new CatalogRepository() {
            public String payloadOf(Object entry) { return ec.kuador.board.catalog.infrastructure.CatalogJson.payload(entry); }
            public <T> List<T> list(CatalogType type, Class<T> c) {
                return store.entrySet().stream().filter(e -> e.getKey().startsWith(type + "/")).sorted(Map.Entry.comparingByKey())
                        .map(e -> c.cast(e.getValue()[0])).toList(); // como la base: por clave
            }
            public <T> Optional<T> find(CatalogType type, String key, Class<T> c) {
                return Optional.ofNullable(store.get(type + "/" + key)).map(v -> c.cast(v[0]));
            }
            public <T> T save(CatalogType type, String key, T entry, boolean builtIn, Long expected, Class<T> c) {
                Object[] current = store.get(type + "/" + key);
                long version = current == null ? 0 : (long) current[2];
                if (current != null && (expected == null || expected != version)) throw new ConflictException("Otra persona cambió " + key);
                T saved = c.cast(withVersion(entry, builtIn, current == null ? 0 : version + 1));
                store.put(type + "/" + key, new Object[] {saved, builtIn, current == null ? 0L : version + 1});
                return saved;
            }
            public void delete(CatalogType type, String key) {
                store.remove(type + "/" + key);
            }
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("usage", new CatalogUsage() {
            public long projectsUsingMethodology(String k) { return methodologyUsers; }
            public long membersWithRole(String k) { return 0; }
        });
        catalog = new CatalogService(repo, InMemoryCatalog.FACTORY, beans.getBeanProvider(CatalogUsage.class), null);
        catalog.provisionDefaults();
    }

    private static Object withVersion(Object entry, boolean builtIn, long version) {
        return switch (entry) {
            case Methodology m -> new Methodology(UUID.randomUUID(), m.key(), m.name(), m.summary(), m.agentRules(), m.documents(), builtIn, version);
            case AgentRole r -> new AgentRole(UUID.randomUUID(), r.key(), r.name(), r.description(), r.instructions(), r.tools(), r.automation(), builtIn, version);
            default -> entry;
        };
    }

    @Test
    void provisioningCopiesTheFactoryCatalog() {
        assertThat(catalog.methodologies()).extracting(Methodology::key).containsExactly("FREE", "MANUAL", "ODD", "SDD");
        assertThat(catalog.methodology("SDD")).get().extracting(Methodology::builtIn).isEqualTo(true);
    }

    @Test
    void editingRequiresTheCurrentVersionAndRestoreBringsBackTheFactoryValues() {
        Methodology sdd = catalog.methodology("SDD").orElseThrow();
        Methodology renamed = new Methodology(null, "SDD", "SDD de Kuador", sdd.summary(), sdd.agentRules(), sdd.documents(), false, sdd.version());

        catalog.save(CatalogType.METHODOLOGY, "SDD", renamed);
        assertThat(catalog.methodology("SDD")).get().extracting(Methodology::name, Methodology::builtIn).containsExactly("SDD de Kuador", true);

        assertThatThrownBy(() -> catalog.save(CatalogType.METHODOLOGY, "SDD", renamed)).as("versión vieja").isInstanceOf(ConflictException.class);

        catalog.restore(CatalogType.METHODOLOGY, "SDD");
        assertThat(catalog.methodology("SDD")).get().extracting(Methodology::name).isEqualTo("Spec Driven Development (Spec Kit)");
    }

    @Test
    void existingWorkspacesReceiveNewFactoryEntriesOnceWithoutTouchingTheirOwn() {
        // Un espacio creado con la versión 1: sin los servidores de GitHub.
        store.remove("MCP_SERVER/GITHUB");
        store.remove("MCP_SERVER/GITHUB_LOCAL");
        // El equipo ya tenía su propio «GITHUB_LOCAL»: se respeta.
        catalog.save(CatalogType.MCP_SERVER, "GITHUB_LOCAL", new ec.kuador.board.catalog.McpServer(null, "GITHUB_LOCAL", "Nuestro GitHub", "propio",
                ec.kuador.board.catalog.McpServer.Transport.STDIO, "npx", List.of("gh-mcp"), null, List.of(), null, false, 0));

        assertThat(catalog.receiveFactoryAdditions(1)).isEqualTo(1);
        assertThat(catalog.mcpServers()).extracting(s -> s.key()).contains("GITHUB", "GITHUB_LOCAL");
        assertThat(catalog.mcpServers()).filteredOn(s -> s.key().equals("GITHUB_LOCAL")).extracting(s -> s.name()).containsExactly("Nuestro GitHub");
        assertThat(catalog.receiveFactoryAdditions(catalog.factoryVersion())).as("ya está al día").isZero();
    }

    @Test
    void cannotDeleteWhatIsInUseNorRestoreWhatIsNotFromTheFactory() {
        methodologyUsers = 2;
        assertThatThrownBy(() -> catalog.delete(CatalogType.METHODOLOGY, "ODD")).isInstanceOf(ConflictException.class).hasMessageContaining("2 tablero(s)");
        methodologyUsers = 0;
        catalog.delete(CatalogType.METHODOLOGY, "ODD");
        assertThat(catalog.methodology("ODD")).isEmpty();

        Methodology custom = new Methodology(null, "KANBAN", "Kanban", "Resumen", List.of("regla"), List.of(), false, 0);
        catalog.save(CatalogType.METHODOLOGY, "KANBAN", custom);
        assertThatThrownBy(() -> catalog.restore(CatalogType.METHODOLOGY, "KANBAN")).hasMessageContaining("no viene de fábrica");
    }
}
