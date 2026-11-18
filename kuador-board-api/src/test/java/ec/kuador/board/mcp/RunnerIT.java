package ec.kuador.board.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.mcp.application.RunnerService;
import ec.kuador.board.member.application.MemberService;
import ec.kuador.board.member.domain.Member;
import ec.kuador.board.member.domain.MemberKind;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import ec.kuador.board.workspace.domain.Workspace;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * El runner de punta a punta: se empareja como una TV, declara sus roles, toma tarjetas con préstamo y las libera; lo
 * que supervisa una persona lo atiende su máquina y, si no está, otra del equipo; tres cortes y deja de ofrecerse.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class RunnerIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static final Map<String, String> INBOX = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Mail {
        @Bean
        @Primary
        VerificationMailer inbox() {
            return INBOX::put;
        }
    }

    @Autowired SignupService signup;
    @Autowired AuthService auth;
    @Autowired MemberService members;
    @Autowired RunnerService runners;
    @Autowired JdbcClient jdbc;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    record Reply(int status, JsonNode body) {}

    private Reply call(String method, String path, Object body, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) req.header("Authorization", "Bearer " + token);
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        boolean isJson = res.headers().firstValue("Content-Type").orElse("").contains("json") && !res.body().isBlank();
        return new Reply(res.statusCode(), isJson ? json.readTree(res.body()) : null);
    }

    private String person(Workspace w, String name, String email) {
        Member m = TenantContext.call(w.id(), () -> members.create(name, MemberKind.HUMAN, "DEV", null, "#cccccc", null));
        TenantContext.run(w.id(), () -> auth.grantAccess(m.id(), email, "clave-de-prueba-1"));
        return auth.login(email, "clave-de-prueba-1").token();
    }

    /** Empareja una máquina como lo hace el runner: pide el código, la persona lo aprueba y el runner canjea la conexión. */
    private String pair(String session, String machine) throws Exception {
        JsonNode started = call("POST", "/api/runner/pair", Map.of("machineName", machine), null).body();
        String device = started.get("deviceCode").asString();
        String code = started.get("userCode").asString();
        assertThat(code).matches("[A-Z2-9]{4}-[A-Z2-9]{4}");
        assertThat(started.get("verifyUrl").asString()).endsWith("/local-agents/pair?code=" + code);
        assertThat(call("POST", "/api/runner/pair/token", Map.of("deviceCode", device), null).status()).as("todavía sin aprobar").isEqualTo(202);
        assertThat(call("POST", "/api/runner/pair/approve", Map.of("userCode", code.toLowerCase().replace("-", "")), session).body()
                .get("machineName").asString()).isEqualTo(machine);
        Reply token = call("POST", "/api/runner/pair/token", Map.of("deviceCode", device), null);
        assertThat(token.status()).isEqualTo(200);
        assertThat(call("POST", "/api/runner/pair/token", Map.of("deviceCode", device), null).status()).as("se entrega una sola vez").isEqualTo(410);
        return token.body().get("token").asString();
    }

    private UUID register(String runner, String name, List<String> roles, int capacity) throws Exception {
        UUID id = UUID.randomUUID();
        Reply r = call("PUT", "/api/runner/heartbeat", Map.of("machineId", id, "name", name, "os", "macos", "tool", "claude", "roles", roles,
                "capacity", capacity, "version", "0.1.0", "register", true), runner);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("online").asBoolean()).isTrue();
        return id;
    }

    /** Llama una herramienta del servidor MCP y devuelve su resultado (con isError). */
    private JsonNode mcp(String token, String tool, Map<String, String> args) throws Exception {
        return call("POST", "/mcp", Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of("name", tool, "arguments", args)), token)
                .body().get("result");
    }

    private Reply claim(String runner, UUID machine) throws Exception {
        return call("POST", "/api/runner/claim", Map.of("machineId", machine), runner);
    }

    @Test
    void runnersPairDeclareRolesAndShareTheWorkWithLeases() throws Exception {
        signup.requestCode("ana@run.test");
        Workspace w = signup.complete(signup.verifyCode("ana@run.test", INBOX.get("ana@run.test")),
                new SignupService.Company("Run", null, null, null, null, null), "Ana", "clave-de-ana-123", "FACTORY");
        String ana = auth.login("ana@run.test", "clave-de-ana-123").token();
        String luis = person(w, "Luis", "luis@run.test");

        String anaRunner = pair(ana, "MacBook de Ana");
        assertThat(anaRunner).startsWith("kfr_");
        assertThat(call("POST", "/api/runner/pair/approve", Map.of("userCode", "ZZZZ-ZZZZ"), ana).status()).isEqualTo(404);
        // La conexión del runner no abre el resto de la API ni la administración de máquinas.
        assertThat(call("GET", "/api/projects", null, anaRunner).status()).isEqualTo(401);
        assertThat(call("GET", "/api/runner/machines", null, anaRunner).status()).isEqualTo(401);
        // Y una sesión web no se hace pasar por un runner.
        assertThat(call("GET", "/api/runner/setup", null, ana).status()).isEqualTo(403);

        // El asistente de instalación ofrece los roles de los agentes del espacio y los tableros que ve.
        JsonNode setup = call("GET", "/api/runner/setup", null, anaRunner).body();
        assertThat(setup.get("person").asString()).isEqualTo("Ana");
        List<String> roles = new ArrayList<>();
        setup.get("roles").forEach(r -> roles.add(r.get("key").asString()));
        assertThat(roles).contains("PRODUCT_OWNER", "BACKEND", "FRONTEND");
        assertThat(setup.get("mcpUrl").asString()).endsWith("/mcp");
        assertThat(call("PUT", "/api/runner/heartbeat", Map.of("machineId", UUID.randomUUID(), "roles", List.of("NADIE"), "register", true), anaRunner)
                .status()).isEqualTo(422);

        UUID anaMachine = register(anaRunner, "MacBook de Ana", List.of("PRODUCT_OWNER", "BACKEND"), 1);
        assertThat(claim(anaRunner, anaMachine).status()).as("sin trabajo").isEqualTo(204);

        // Ana crea una historia en Refinamiento (la cubre el agente PO): la toma su máquina, ya a nombre del agente.
        call("POST", "/api/projects", Map.of("key", "RUN", "name", "Run", "templateKey", "SOFTWARE_FACTORY"), ana);
        call("POST", "/api/projects/RUN/people", Map.of("memberId", call("GET", "/api/auth/me", null, luis).body().get("id").asString()), ana);
        String story = call("POST", "/api/projects/RUN/issues", Map.of("title", "Agenda de citas", "type", "STORY", "status", "REFINEMENT"), ana)
                .body().get("key").asString();
        Reply first = claim(anaRunner, anaMachine);
        assertThat(first.status()).isEqualTo(200);
        JsonNode c = first.body();
        assertThat(c.get("issueKey").asString()).isEqualTo(story);
        assertThat(c.get("role").asString()).isEqualTo("PRODUCT_OWNER");
        assertThat(c.get("projectKind").asString()).isEqualTo("SOFTWARE");
        assertThat(c.get("prompt").asString()).contains(story, "agent: PRODUCT_OWNER");
        assertThat(c.get("instructions").asString()).contains("role_manual");
        assertThat(c.get("allowedTools").toString()).contains("mcp__run__deliver");
        assertThat(call("GET", "/api/agents/runs?issueKey=" + story, null, ana).body().get(0).get("status").asString()).isEqualTo("RUNNING");

        // La credencial de esta ejecución: solo esta tarjeta, solo este rol y solo el servidor MCP.
        String runToken = c.get("runToken").asString();
        assertThat(runToken).startsWith("kfx_");
        assertThat(mcp(runToken, "get_issue", Map.of("key", story)).get("isError").asBoolean()).isFalse();
        assertThat(mcp(runToken, "role_manual", Map.of("agent", "PRODUCT_OWNER")).get("isError").asBoolean()).isFalse();
        JsonNode otherRole = mcp(runToken, "add_comment", Map.of("key", story, "agent", "BACKEND", "text", "hola"));
        assertThat(otherRole.get("isError").asBoolean()).isTrue();
        assertThat(otherRole.get("content").get(0).get("text").asString()).contains("trabaja como PRODUCT_OWNER");
        JsonNode otherBoard = mcp(runToken, "read_document", Map.of("type", "SPEC", "project", "OTRO"));
        assertThat(otherBoard.get("content").get(0).get("text").asString()).contains("solo lee el tablero RUN");
        assertThat(call("GET", "/api/runner/setup", null, runToken).status()).as("no abre las rutas del runner").isEqualTo(401);
        assertThat(call("GET", "/api/projects", null, runToken).status()).isEqualTo(401);
        assertThat(claim(anaRunner, anaMachine).status()).as("capacidad 1: ya tiene una").isEqualTo(204);
        assertThat(call("POST", "/api/runner/leases/" + story + "/renew", Map.of("machineId", anaMachine), anaRunner).status()).isEqualTo(200);
        JsonNode mine = call("GET", "/api/runner/machines", null, ana).body().get(0);
        assertThat(mine.get("working").get(0).asString()).isEqualTo(story);
        assertThat(mine.get("roles").toString()).contains("PRODUCT_OWNER");

        // El cliente de IA terminó sin entregar: no avanzó, cuenta como corte (1).
        assertThat(call("POST", "/api/runner/leases/" + story + "/release", Map.of("machineId", anaMachine, "outcome", "DONE"), anaRunner).status())
                .isEqualTo(204);
        assertThat(call("GET", "/api/agents/runs?issueKey=" + story, null, ana).body().get(0).get("status").asString()).isEqualTo("FAILED");
        assertThat(call("POST", "/mcp", Map.of("jsonrpc", "2.0", "id", 9, "method", "tools/list"), runToken).status())
                .as("al liberar la tarjeta, su credencial muere").isEqualTo(401);
        assertThat(call("POST", "/api/runner/leases/" + story + "/renew", Map.of("machineId", anaMachine), anaRunner).status())
                .as("el préstamo ya no es suyo").isEqualTo(409);

        // Luis también tiene runner con el rol PO. Lo que supervisa Ana lo atiende la máquina de Ana mientras esté prendida.
        String luisRunner = pair(luis, "PC de Luis");
        UUID luisMachine = register(luisRunner, "PC de Luis", List.of("PRODUCT_OWNER"), 2);
        assertThat(claim(luisRunner, luisMachine).status()).isEqualTo(204);
        assertThat(call("POST", "/api/runner/claim", Map.of("machineId", anaMachine), luisRunner).status()).as("máquina ajena").isEqualTo(404);

        // Ana pausa su máquina desde el tablero: ahora la toma la de Luis, que pasa a supervisarla.
        assertThat(call("PATCH", "/api/runner/machines/" + anaMachine, Map.of("paused", true), ana).body().get("paused").asBoolean()).isTrue();
        assertThat(call("PATCH", "/api/runner/machines/" + anaMachine, Map.of("paused", false), luis).status()).as("la de otra persona").isEqualTo(404);
        Reply taken = claim(luisRunner, luisMachine);
        assertThat(taken.status()).isEqualTo(200);
        assertThat(taken.body().get("issueKey").asString()).isEqualTo(story);
        assertThat(claim(anaRunner, anaMachine).status()).as("pausada").isEqualTo(204);

        // La PC de Luis se apaga sin avisar: el préstamo vence y la tarjeta vuelve a la cola (corte 2).
        jdbc.sql("update runner_lease set expires_at = now() - interval '1 minute' where issue_key = ? and released_at is null").param(story).update();
        runners.expire();
        assertThat(call("GET", "/api/agents/runs?issueKey=" + story, null, ana).body().get(0).get("error").asString()).contains("PC de Luis");
        Reply again = claim(luisRunner, luisMachine);
        assertThat(again.status()).as("sigue supervisándola Luis: su máquina la retoma").isEqualTo(200);

        // Falla una vez más (corte 3): deja de ofrecerse y queda un aviso en la tarjeta.
        call("POST", "/api/runner/leases/" + story + "/release", Map.of("machineId", luisMachine, "outcome", "FAILED", "message", "claude salió con 1"),
                luisRunner);
        assertThat(claim(luisRunner, luisMachine).status()).isEqualTo(204);
        assertThat(call("GET", "/api/issues/" + story + "/comments", null, ana).body().toString()).contains("no logré terminarlo");

        // Con la suscripción de una persona el freno es de tiempo: llegado su tope del día, la máquina no toma más.
        JsonNode capped = call("PATCH", "/api/runner/machines/" + luisMachine, Map.of("dailyMinutes", 15), luis).body();
        assertThat(capped.get("dailyMinutes").asInt()).isEqualTo(15);
        assertThat(call("PATCH", "/api/runner/machines/" + luisMachine, Map.of("dailyMinutes", 5), luis).status()).isEqualTo(422);
        jdbc.sql("update runner_lease set started_at = now() - interval '30 minutes' where machine_id = ?").param(luisMachine).update();
        String another = call("POST", "/api/projects/RUN/issues", Map.of("title", "Recordatorios", "type", "STORY", "status", "REFINEMENT"), luis)
                .body().get("key").asString();
        assertThat(claim(luisRunner, luisMachine).status()).as("ya trabajó más que su tope de hoy").isEqualTo(204);
        assertThat(call("PATCH", "/api/runner/machines/" + luisMachine, Map.of("noDailyLimit", true), luis).body().get("dailyMinutes").isNull()).isTrue();
        assertThat(claim(luisRunner, luisMachine).body().get("issueKey").asString()).isEqualTo(another);

        // Las máquinas se pueden quitar desde el tablero.
        assertThat(call("DELETE", "/api/runner/machines/" + anaMachine, null, ana).status()).isEqualTo(204);
        JsonNode left = call("GET", "/api/runner/machines", null, ana).body();
        assertThat(left.size()).as("quien administra ve las del equipo").isEqualTo(1);
        assertThat(left.get(0).get("person").asString()).isEqualTo("Luis");
        assertThat(call("GET", "/api/runner/machines", null, luis).body().get(0).get("name").asString()).isEqualTo("PC de Luis");
    }
}
