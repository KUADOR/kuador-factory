package ec.kuador.board.connections.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.connections.ConnectionsApi;
import ec.kuador.board.connections.domain.Connection;
import ec.kuador.board.connections.domain.ConnectionStore;
import ec.kuador.board.connections.domain.McpClient;
import ec.kuador.board.connections.domain.OAuthClient;
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
    private final SecretBox box = new SecretBox(Base64.getEncoder().encodeToString(new byte[32]));
    private final FakeOAuth oauth = new FakeOAuth();
    private final MutableClock clock = new MutableClock();
    private final ConnectionService service = new ConnectionService(store, mcp, box, new OAuthSessions(oauth, box, clock, "https://go.example/"), clock, true);
    private static final UUID ADMIN = UUID.randomUUID();

    @Test
    void aSystemThatAsksToSignInIsConnectedWithOAuthAndKeepsItsSessionAlive() {
        TenantContext.run(WS, () -> {
            mcp.expects = "acceso-1";
            String go = service.startLogin("correo", "Correo", "https://correo.example/mcp", null, null, null, ADMIN);

            assertThat(go).startsWith("https://login.example/authorize?response_type=code&client_id=cliente-registrado")
                    .contains("redirect_uri=https%3A%2F%2Fgo.example%2Fconnections%2Foauth%2Fcallback", "code_challenge_method=S256",
                            "resource=https%3A%2F%2Fcorreo.example%2Fmcp", "scope=mail.read+offline_access");
            String state = param(go, "state");
            ConnectionService.View pending = service.list().getFirst();
            assertThat(pending.auth()).isEqualTo("OAUTH");
            assertThat(pending.connected()).as("hasta volver con el código no está conectada").isFalse();
            assertThat(pending.tools()).isEmpty();

            assertThatThrownBy(() -> service.finishLogin("otro-estado", "codigo", ADMIN)).isInstanceOf(DomainException.class).hasMessageContaining("venció o no corresponde");
            assertThatThrownBy(() -> service.finishLogin(state, "codigo", UUID.randomUUID())).as("solo quien lo empezó lo termina").isInstanceOf(DomainException.class);

            ConnectionService.View done = service.finishLogin(state, "codigo", ADMIN);
            assertThat(done.connected()).isTrue();
            assertThat(done.tools()).hasSize(2);
            assertThat(oauth.verifierMatchesChallenge(param(go, "code_challenge"))).as("PKCE: el código se canjea con el verificador del desafío").isTrue();
            assertThat(store.find(WS, "CORREO").orElseThrow().oauth()).as("cifrada en reposo").doesNotContain("acceso-1");
            assertThatThrownBy(() -> service.finishLogin(state, "codigo", ADMIN)).as("el estado se usa una sola vez").isInstanceOf(DomainException.class);

            service.setBoardUses("CLI", List.of(new ConnectionService.BoardUseRequest("CORREO", List.of())));
            assertThat(service.call("CLI", "ANALYST", "correo__buscar_citas", Map.of(), AGENT, "CLI-1")).isEqualTo("ok buscar_citas");
            assertThat(oauth.refreshes).isZero();

            // Por vencer: se renueva antes de llamar.
            clock.advance(3590);
            mcp.expects = "acceso-2";
            assertThat(service.call("CLI", "ANALYST", "correo__buscar_citas", Map.of(), AGENT, "CLI-1")).isEqualTo("ok buscar_citas");
            assertThat(oauth.refreshes).isEqualTo(1);

            // El servidor lo revoca antes de tiempo: se renueva una vez y se reintenta.
            mcp.expects = "acceso-3";
            assertThat(service.call("CLI", "ANALYST", "correo__buscar_citas", Map.of(), AGENT, "CLI-1")).isEqualTo("ok buscar_citas");
            assertThat(oauth.refreshes).isEqualTo(2);
            assertThat(oauth.lastRefreshToken).as("el token de renovación rotado es el que se usa después").isEqualTo("renovar-2");

            // Cambiar el nombre no cierra la sesión.
            assertThat(service.save("CORREO", "Correo de ventas", "https://correo.example/mcp", null, null).connected()).isTrue();

            // Ya no se puede renovar: se pide volver a iniciar sesión, y hacerlo reutiliza el cliente registrado.
            oauth.refreshFails = true;
            mcp.expects = "acceso-9";
            assertThatThrownBy(() -> service.call("CLI", "ANALYST", "correo__buscar_citas", Map.of(), AGENT, "CLI-1"))
                    .isInstanceOf(DomainException.class).hasMessageContaining("vuelve a iniciar sesión");
            service.startLogin("CORREO", "Correo de ventas", "https://correo.example/mcp", null, null, null, ADMIN);
            assertThat(oauth.registrations).as("no se registra dos veces").isEqualTo(1);
            assertThat(service.toolsFor("CLI", "ANALYST")).as("mientras se vuelve a entrar, conserva lo asignado").hasSize(1);
        });
    }

    @Test
    void aSystemThatDoesNotLetTheBoardRegisterNeedsAClientOfItsOwn() {
        TenantContext.run(WS, () -> {
            oauth.registration = null;
            assertThatThrownBy(() -> service.startLogin("OUTLOOK", "Outlook", "https://outlook.example/mcp", null, null, null, ADMIN))
                    .isInstanceOf(DomainException.class).hasMessageContaining("https://go.example/connections/oauth/callback");
            assertThat(service.list()).isEmpty();

            String go = service.startLogin("OUTLOOK", "Outlook", "https://outlook.example/mcp", "app-de-la-empresa", "secreto", "Mail.Read offline_access", ADMIN);
            assertThat(go).contains("client_id=app-de-la-empresa", "scope=Mail.Read+offline_access").doesNotContain("secreto");
            mcp.expects = "acceso-1";
            service.finishLogin(param(go, "state"), "codigo", ADMIN);
            assertThat(oauth.lastClient).isEqualTo(new OAuthClient.Client("app-de-la-empresa", "secreto"));
        });
    }

    private static String param(String url, String name) {
        for (String part : url.substring(url.indexOf('?') + 1).split("&")) {
            if (part.startsWith(name + "=")) return java.net.URLDecoder.decode(part.substring(name.length() + 1), java.nio.charset.StandardCharsets.UTF_8);
        }
        throw new AssertionError("falta " + name);
    }

    private static final class MutableClock extends Clock {
        private java.time.Instant now = java.time.Instant.parse("2026-11-23T12:00:00Z");

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }

    /** Servidor de autorización de mentira: entrega acceso-N y renovar-N, y rota el token de renovación. */
    private static final class FakeOAuth implements OAuthClient {
        String registration = "https://login.example/register";
        int registrations;
        int refreshes;
        int issued;
        boolean refreshFails;
        String lastRefreshToken;
        String lastVerifier;
        Client lastClient;

        @Override
        public Discovery discover(String mcpUrl) {
            return new Discovery("https://login.example/authorize", "https://login.example/token", registration, mcpUrl, List.of("mail.read", "offline_access"));
        }

        @Override
        public Client register(String registrationEndpoint, String redirectUri, String clientName) {
            registrations++;
            return new Client("cliente-registrado", null);
        }

        @Override
        public Tokens exchange(Discovery where, Client client, String code, String verifier, String redirectUri) {
            lastVerifier = verifier;
            lastClient = client;
            issued++;
            return new Tokens("acceso-" + issued, "renovar-" + issued, 3600);
        }

        @Override
        public Tokens refresh(String tokenEndpoint, Client client, String refreshToken, String resource) {
            if (refreshFails) throw new OAuthException("invalid_grant");
            refreshes++;
            lastRefreshToken = refreshToken;
            issued++;
            return new Tokens("acceso-" + issued, "renovar-" + issued, 3600);
        }

        boolean verifierMatchesChallenge(String challenge) {
            return OAuthSessions.challenge(lastVerifier).equals(challenge);
        }
    }

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
        /** Si no es nulo, el único token que el servidor acepta. */
        String expects;
        final List<String> secrets = new ArrayList<>();

        @Override
        public List<Connection.ToolDef> listTools(String url, String header, String secret) {
            if (down) throw new McpException("no responde");
            if (expects != null && !expects.equals(secret)) throw new UnauthorizedException("El servidor rechazó la credencial (401).");
            if (secret != null) secrets.add(secret);
            return List.of(new Connection.ToolDef("buscar_citas", "Citas de un día", Map.of("type", "object"), true),
                    new Connection.ToolDef("crear_cita", "Agenda una cita", Map.of("type", "object"), false));
        }

        @Override
        public String callTool(String url, String header, String secret, String tool, Map<String, Object> arguments) {
            if (expects != null && !expects.equals(secret)) throw new UnauthorizedException("El servidor rechazó la credencial (401).");
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
