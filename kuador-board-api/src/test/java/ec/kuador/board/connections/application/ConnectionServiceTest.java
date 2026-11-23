package ec.kuador.board.connections.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.connections.ConnectionsApi;
import ec.kuador.board.connections.domain.Connection;
import ec.kuador.board.connections.domain.ConnectionStore;
import ec.kuador.board.connections.domain.McpClient;
import ec.kuador.board.shared.crypto.SecretBox;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConnectionServiceTest {

    private static final UUID WS = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();

    private final InMemoryStore store = new InMemoryStore();
    private final FakeMcp mcp = new FakeMcp();
    private final ConnectionService service = new ConnectionService(store, mcp, new SecretBox(Base64.getEncoder().encodeToString(new byte[32])),
            Clock.systemUTC(), true);

    @Test
    void anAgentGetsReadingToolsAndOnlyTheWritingOnesSomeoneEnabled() {
        TenantContext.run(WS, () -> {
            ConnectionService.View saved = service.save("agenda", "Agenda de la clínica", "https://agenda.example/mcp", null, "token-secreto");
            assertThat(saved.hasSecret()).isTrue();
            assertThat(saved.tools()).extracting(ConnectionService.ToolView::name, ConnectionService.ToolView::allowed)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("buscar_citas", true), org.assertj.core.groups.Tuple.tuple("crear_cita", false));
            assertThat(store.find(WS, "AGENDA").orElseThrow().secret()).as("cifrada en reposo").isNotEqualTo("token-secreto");
            assertThat(mcp.secrets).as("al servidor llega descifrada").containsOnly("token-secreto");

            assertThat(service.toolsFor("CLI", "ANALYST")).as("sin asignar al tablero no hay nada").isEmpty();
            service.setBoardUses("CLI", List.of(new ConnectionService.BoardUseRequest("AGENDA", List.of("ANALYST"))));

            assertThat(service.toolsFor("CLI", "ANALYST")).extracting(ConnectionsApi.Tool::name).containsExactly("agenda__buscar_citas");
            assertThat(service.toolsFor("CLI", "MARKETING")).as("otro rol no la recibe").isEmpty();
            assertThat(service.toolsFor("VEN", "ANALYST")).as("otro tablero tampoco").isEmpty();

            assertThat(service.call("CLI", "ANALYST", "agenda__buscar_citas", Map.of("dia", "lunes"), AGENT, "CLI-1")).isEqualTo("ok buscar_citas");
            assertThatThrownBy(() -> service.call("CLI", "ANALYST", "agenda__crear_cita", Map.of(), AGENT, "CLI-1"))
                    .as("una herramienta que escribe no se usa hasta que alguien la habilita").isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> service.call("CLI", "MARKETING", "agenda__buscar_citas", Map.of(), AGENT, "CLI-1")).isInstanceOf(DomainException.class);

            service.allowWrites("AGENDA", List.of("crear_cita"));
            assertThat(service.toolsFor("CLI", "ANALYST")).extracting(ConnectionsApi.Tool::name, ConnectionsApi.Tool::readOnly)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("agenda__buscar_citas", true), org.assertj.core.groups.Tuple.tuple("agenda__crear_cita", false));
            assertThat(service.call("CLI", "ANALYST", "agenda__crear_cita", Map.of(), AGENT, "CLI-1")).isEqualTo("ok crear_cita");
            assertThatThrownBy(() -> service.allowWrites("AGENDA", List.of("buscar_citas"))).as("solo se habilita lo que escribe").isInstanceOf(DomainException.class);
        });
    }

    @Test
    void aConnectionThatDoesNotAnswerIsNotSaved() {
        TenantContext.run(WS, () -> {
            mcp.down = true;
            assertThatThrownBy(() -> service.save("ERP", "ERP", "https://erp.example/mcp", null, null)).isInstanceOf(DomainException.class)
                    .hasMessageContaining("No se pudo conectar");
            assertThat(service.list()).isEmpty();
        });
    }

    @Test
    void keepsTheSecretWhenEditingWithoutSendingItAgain() {
        TenantContext.run(WS, () -> {
            service.save("CRM", "CRM", "https://crm.example/mcp", null, "uno");
            String stored = store.find(WS, "CRM").orElseThrow().secret();
            service.save("CRM", "CRM de ventas", "https://crm.example/mcp", null, null);
            assertThat(store.find(WS, "CRM").orElseThrow().secret()).isEqualTo(stored);
            assertThat(service.save("CRM", "CRM de ventas", "https://crm.example/mcp", null, "").hasSecret()).isFalse();
        });
    }

    private static final class FakeMcp implements McpClient {
        boolean down;
        final List<String> secrets = new ArrayList<>();

        @Override
        public List<Connection.ToolDef> listTools(String url, String header, String secret) {
            if (down) throw new McpException("no responde");
            if (secret != null) secrets.add(secret);
            return List.of(new Connection.ToolDef("buscar_citas", "Citas de un día", Map.of("type", "object"), true),
                    new Connection.ToolDef("crear_cita", "Agenda una cita", Map.of("type", "object"), false));
        }

        @Override
        public String callTool(String url, String header, String secret, String tool, Map<String, Object> arguments) {
            return "ok " + tool;
        }
    }

    private static final class InMemoryStore implements ConnectionStore {
        private final Map<String, Connection> connections = new HashMap<>();
        private final List<Use> uses = new ArrayList<>();

        @Override
        public List<Connection> all(UUID ws) {
            return List.copyOf(connections.values());
        }

        @Override
        public Optional<Connection> find(UUID ws, String key) {
            return Optional.ofNullable(connections.get(key));
        }

        @Override
        public void save(UUID ws, Connection c) {
            connections.put(c.key(), c);
        }

        @Override
        public void delete(UUID ws, String key) {
            connections.remove(key);
            uses.removeIf(u -> u.connection().equals(key));
        }

        @Override
        public List<Use> uses(UUID ws) {
            return List.copyOf(uses);
        }

        @Override
        public List<Use> usesOf(UUID ws, String projectKey) {
            return uses.stream().filter(u -> u.projectKey().equals(projectKey)).toList();
        }

        @Override
        public void saveUses(UUID ws, String projectKey, List<Use> fresh) {
            deleteUsesOfProject(ws, projectKey);
            uses.addAll(fresh);
        }

        @Override
        public void deleteUsesOfProject(UUID ws, String projectKey) {
            uses.removeIf(u -> u.projectKey().equals(projectKey));
        }
    }
}
