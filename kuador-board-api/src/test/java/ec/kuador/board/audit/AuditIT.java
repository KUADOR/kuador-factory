package ec.kuador.board.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ec.kuador.board.auth.application.AuthService;
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
import java.util.List;
import java.util.Map;
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
 * La auditoría registra quién entró (y quién falló), qué cambió y lo que se le negó, con la IP de la persona; solo la
 * ve quien edita, se exporta y no se puede modificar.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=", "board.agents.anthropic-api-key=",
        "board.sync.allowed-roots=", "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class AuditIT {

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
    @Autowired JdbcClient jdbc;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    record Reply(int status, String raw, JsonNode body) {}

    private Reply call(String method, String path, Object body, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Kuador-Client-IP", "190.152.10.20")
                .header("X-Kuador-Client-Agent", "Chrome de prueba")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) req.header("Authorization", "Bearer " + token);
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        boolean isJson = res.headers().firstValue("Content-Type").orElse("").contains("json") && !res.body().isBlank();
        return new Reply(res.statusCode(), res.body(), isJson ? json.readTree(res.body()) : null);
    }

    @Test
    void recordsAccessChangesAndDenialsAndCannotBeAltered() throws Exception {
        signup.requestCode("ana@ventas.test");
        Workspace w = signup.complete(signup.verifyCode("ana@ventas.test", INBOX.get("ana@ventas.test")),
                new SignupService.Company("Ventas Ana", null, null, null, null, null), "Ana", "clave-de-ana-123", "TEAM");
        Member viewerMember = TenantContext.call(w.id(), () -> members.create("Mirón", MemberKind.HUMAN, "CLIENTE", null, "#cccccc", null));
        TenantContext.run(w.id(), () -> auth.grantAccess(viewerMember.id(), "miron@ventas.test", "clave-del-miron-1"));

        assertThat(call("POST", "/api/auth/login", Map.of("email", "ana@ventas.test", "password", "equivocada"), null).status()).isEqualTo(401);
        String owner = call("POST", "/api/auth/login", Map.of("email", "ana@ventas.test", "password", "clave-de-ana-123"), null).body().get("token").asString();
        String viewer = call("POST", "/api/auth/login", Map.of("email", "miron@ventas.test", "password", "clave-del-miron-1"), null).body().get("token").asString();

        call("POST", "/api/projects", Map.of("key", "VEN", "name", "Ventas", "templateKey", "SALES"), owner);
        call("POST", "/api/projects/VEN/issues", Map.of("title", "Cliente nuevo", "type", "TASK"), owner);
        assertThat(call("DELETE", "/api/projects/VEN?confirm=VEN", null, viewer).status()).isEqualTo(403);
        assertThat(call("GET", "/api/workspace/audit", null, viewer).status()).as("quien solo mira no ve la auditoría").isEqualTo(403);

        JsonNode page = call("GET", "/api/workspace/audit?limit=50", null, owner).body();
        List<String> labels = page.get("items").valueStream().map(i -> i.get("label").asString() + "|" + i.get("outcome").asString()).toList();
        assertThat(labels).contains("Inicio de sesión|OK", "Creó un tablero|OK", "Creó una tarjeta|OK", "Eliminó un tablero|DENIED");
        JsonNode login = page.get("items").valueStream().filter(i -> i.get("label").asString().equals("Inicio de sesión")
                && i.get("actorName").asString("").equals("Ana")).findFirst().orElseThrow();
        assertThat(login.get("target").asString()).isEqualTo("ana@ventas.test");
        assertThat(login.get("ip").asString()).isEqualTo("190.152.10.20");
        assertThat(login.get("userAgent").asString()).isEqualTo("Chrome de prueba");
        assertThat(page.toString()).as("nunca guarda contraseñas").doesNotContain("clave-de-ana", "equivocada");

        JsonNode security = call("GET", "/api/workspace/audit?category=SECURITY", null, owner).body();
        assertThat(security.get("items").findValuesAsString("label")).contains("Creó un tablero").doesNotContain("Creó una tarjeta");

        // El intento fallido no tiene espacio (no se sabe de quién era): lo ve la plataforma.
        assertThat(jdbc.sql("select count(*) from audit_log where workspace_id is null and target = 'ana@ventas.test' and status = 401").query(Long.class).single())
                .isEqualTo(1);

        Reply csv = call("GET", "/api/workspace/audit/export", null, owner);
        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.raw()).contains("fecha_utc,persona", "Creó un tablero", "190.152.10.20");

        assertThatThrownBy(() -> jdbc.sql("update audit_log set actor_name = 'otro'").update()).hasMessageContaining("no se modifica");
        assertThatThrownBy(() -> jdbc.sql("delete from audit_log").update()).hasMessageContaining("no se modifica");
    }
}
