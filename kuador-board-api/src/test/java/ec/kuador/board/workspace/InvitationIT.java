package ec.kuador.board.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.shared.mail.Mailer;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Invitar por correo: la persona queda en el equipo, abre el enlace, elige su contraseña y entra con su acceso. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "board.web-url=https://tablero.test",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class InvitationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static final Map<String, String> CODES = new ConcurrentHashMap<>();
    static final Map<String, String> MAIL = new ConcurrentHashMap<>();

    @TestConfiguration
    static class Mail {
        @Bean
        @Primary
        VerificationMailer codes() {
            return CODES::put;
        }

        @Bean
        @Primary
        Mailer mail() {
            return (to, subject, text) -> MAIL.put(to, subject + "\n" + text);
        }
    }

    @Autowired SignupService signup;
    @Autowired AuthService auth;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    record Reply(int status, JsonNode body) {}

    private Reply call(String method, String path, Object body, String token) throws Exception {
        var r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) r.header("Authorization", "Bearer " + token);
        HttpResponse<String> res = http.send(r.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(), res.body().isBlank() ? null : json.readTree(res.body()));
    }

    private static String token(String mail) {
        Matcher m = Pattern.compile("https://tablero\\.test/invitation\\?token=([A-Za-z0-9_-]+)").matcher(mail);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void anInvitedPersonChoosesTheirPasswordAndJoinsWithTheirAccess() throws Exception {
        signup.requestCode("ines@inv.test");
        signup.complete(signup.verifyCode("ines@inv.test", CODES.get("ines@inv.test")),
                new SignupService.Company("Clínica Inv", null, null, null, null, null), "Inés", "clave-de-ines-123", "TEAM");
        String owner = auth.login("ines@inv.test", "clave-de-ines-123").token();

        Reply invited = call("POST", "/api/invitations", Map.of("name", "Carla Cliente", "role", "CLIENTE", "email", "Carla@Cliente.test"), owner);
        assertThat(invited.status()).isEqualTo(201);
        assertThat(invited.body().get("memberName").asString()).isEqualTo("Carla Cliente");
        String mail = MAIL.get("carla@cliente.test");
        assertThat(mail).startsWith("Inés te invitó a Clínica Inv en Kuador Go");
        String first = token(mail);

        // Reenviar invalida el enlace anterior.
        call("POST", "/api/invitations/" + invited.body().get("id").asString() + "/resend", null, owner);
        String second = token(MAIL.get("carla@cliente.test"));
        assertThat(call("GET", "/api/public/invitations?token=" + first, null, null).status()).isEqualTo(422);

        Reply preview = call("GET", "/api/public/invitations?token=" + URLEncoder.encode(second, StandardCharsets.UTF_8), null, null);
        assertThat(preview.body().get("workspaceName").asString()).isEqualTo("Clínica Inv");
        assertThat(preview.body().get("email").asString()).isEqualTo("carla@cliente.test");

        Reply accepted = call("POST", "/api/public/invitations/accept", Map.of("token", second, "password", "clave-de-carla-123"), null);
        assertThat(accepted.body().get("email").asString()).isEqualTo("carla@cliente.test");
        String carla = auth.login("carla@cliente.test", "clave-de-carla-123").token();
        assertThat(call("GET", "/api/auth/me", null, carla).body().get("access").asString()).as("un cliente solo mira").isEqualTo("VIEW");

        assertThat(call("POST", "/api/public/invitations/accept", Map.of("token", second, "password", "otra-clave-larga"), null).status())
                .as("el enlace sirve una vez").isEqualTo(422);
        assertThat(call("GET", "/api/invitations", null, owner).body().size()).isZero();
        assertThat(call("POST", "/api/invitations", Map.of("name", "Otra", "role", "VENTAS", "email", "carla@cliente.test"), owner).status())
                .as("los correos son únicos").isEqualTo(409);
        assertThat(call("GET", "/api/invitations", null, carla).status()).as("quien solo mira no invita").isEqualTo(403);
    }
}
