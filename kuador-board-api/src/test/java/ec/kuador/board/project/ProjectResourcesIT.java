package ec.kuador.board.project;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lo que es del proyecto (su manual, sus documentos base y su repositorio) se configura una vez y lo comparten los
 * tableros que lo trabajan. Lo ven quienes trabajan el proyecto en un tablero que usa documentos o repositorio.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class ProjectResourcesIT {

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        ec.kuador.board.testing.SharedPostgres.register(registry);
    }

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
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    record Reply(int status, JsonNode body) {}

    private Reply call(String method, String path, Object body, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) req.header("Authorization", "Bearer " + token);
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(), res.body().isBlank() ? null : json.readTree(res.body()));
    }

    private String owner(String mail, String company) {
        signup.requestCode(mail);
        signup.complete(signup.verifyCode(mail, INBOX.get(mail)), new SignupService.Company(company, null, null, null, null, null), "Sol", "clave-de-sol-123", "FACTORY");
        return auth.login(mail, "clave-de-sol-123").token();
    }

    private String editor(String owner, String mail, String name) throws Exception {
        String id = call("POST", "/api/members", Map.of("name", name, "kind", "HUMAN", "role", "DEV", "avatarColor", "#cccccc"), owner).body().get("id").asString();
        UUID ws = UUID.fromString(call("GET", "/api/auth/me", null, owner).body().get("workspaceId").asString());
        ec.kuador.board.shared.tenant.TenantContext.run(ws, () -> auth.grantAccess(UUID.fromString(id), mail, "clave-de-edu-123"));
        return id;
    }

    private String teamOf(String board, String owner) throws Exception {
        return call("GET", "/api/projects/" + board, null, owner).body().get("team").get("id").asString();
    }

    @Test
    void twoBoardsOfOneProjectShareItsManualDocumentsAndRepository() throws Exception {
        String owner = owner("sol@compartido.test", "Compartido");
        call("POST", "/api/subjects", Map.of("key", "GYM", "name", "Desarrollo gimnasio"), owner);
        call("POST", "/api/projects", Map.of("key", "DEV", "name", "Desarrollo", "templateKey", "SOFTWARE", "subjects", List.of("GYM")), owner);
        call("POST", "/api/projects", Map.of("key", "SOP", "name", "Soporte", "templateKey", "SOFTWARE", "subjects", List.of("GYM")), owner);
        call("POST", "/api/projects", Map.of("key", "MKT", "name", "Marketing", "templateKey", "TASKS", "subjects", List.of("GYM")), owner);

        // El manual se elige una vez, en el proyecto, y vale en los dos tableros de código; Marketing no usa documentos.
        assertThat(call("PUT", "/api/subjects/GYM/methodology", Map.of("methodology", "ODD"), owner).body().get("methodology").asString()).isEqualTo("ODD");
        assertThat(call("GET", "/api/projects/DEV", null, owner).body().get("methodology").asString()).isEqualTo("ODD");
        assertThat(call("GET", "/api/projects/SOP", null, owner).body().get("methodology").asString()).isEqualTo("ODD");
        assertThat(call("GET", "/api/projects/MKT", null, owner).body().get("methodology").isNull()).isTrue();

        // Un documento base creado desde el proyecto se ve desde los dos tableros: es uno solo.
        JsonNode product = call("POST", "/api/subjects/GYM/docs", Map.of("kind", "PRODUCT"), owner).body();
        String productId = product.get("document").get("id").asString();
        assertThat(call("POST", "/api/projects/SOP/docs", Map.of("kind", "PRODUCT"), owner).status()).as("ya existe en el proyecto").isEqualTo(409);
        assertThat(ids("/api/projects/SOP/docs", owner)).contains(productId);
        assertThat(ids("/api/subjects/GYM/docs", owner)).contains(productId);

        // El repositorio también: lo que se ve desde un tablero es lo del proyecto.
        jdbc.sql("""
                insert into project_repository (id, workspace_id, project_key, subject_key, kind, location, branch, secret, linked_at)
                select gen_random_uuid(), workspace_id, 'DEV', 'GYM', 'GITHUB_APP', 'acme/gimnasio', 'main', '123', now() from subject
                 where subject_key = 'GYM' and name = 'Desarrollo gimnasio'""").update();
        assertThat(call("GET", "/api/projects/SOP/repository", null, owner).body().get("location").asString()).isEqualTo("acme/gimnasio");
        assertThat(call("GET", "/api/subjects/GYM/repository", null, owner).body().get("location").asString()).isEqualTo("acme/gimnasio");

        // Borrar un tablero no se lleva lo del proyecto.
        assertThat(call("DELETE", "/api/projects/DEV?confirm=DEV", null, owner).status()).isEqualTo(204);
        assertThat(call("GET", "/api/projects/SOP/repository", null, owner).body().get("location").asString()).isEqualTo("acme/gimnasio");
        assertThat(ids("/api/projects/SOP/docs", owner)).contains(productId);

        // Un proyecto sin tableros de código no tiene por dónde conectar un repositorio.
        call("POST", "/api/subjects", Map.of("key", "SOLO", "name", "Sin tableros"), owner);
        assertThat(call("GET", "/api/subjects/SOLO/repository", null, owner).status()).isEqualTo(422);
    }

    @Test
    void theProjectDocumentsAreSeenByTheTeamsThatWorkItWithDocuments() throws Exception {
        String owner = owner("sol@acceso-proyecto.test", "Acceso al proyecto");
        call("POST", "/api/subjects", Map.of("key", "GYM", "name", "Gimnasio"), owner);
        call("POST", "/api/projects", Map.of("key", "DEV", "name", "Desarrollo", "templateKey", "SOFTWARE", "subjects", List.of("GYM")), owner);
        call("POST", "/api/projects", Map.of("key", "MKT", "name", "Marketing", "templateKey", "TASKS", "subjects", List.of("GYM")), owner);
        call("PUT", "/api/subjects/GYM/methodology", Map.of("methodology", "ODD"), owner);
        String productId = call("POST", "/api/projects/DEV/docs", Map.of("kind", "PRODUCT"), owner).body().get("document").get("id").asString();

        String devId = editor(owner, "dev@acceso-proyecto.test", "Dev");
        String mktId = editor(owner, "mkt@acceso-proyecto.test", "Mkt");
        call("POST", "/api/teams/" + teamOf("DEV", owner) + "/members", Map.of("memberId", devId), owner);
        call("POST", "/api/teams/" + teamOf("MKT", owner) + "/members", Map.of("memberId", mktId), owner);
        String dev = auth.login("dev@acceso-proyecto.test", "clave-de-edu-123").token();
        String mkt = auth.login("mkt@acceso-proyecto.test", "clave-de-edu-123").token();

        // Marketing trabaja el proyecto (lo ve y lo elige) pero su flujo no usa documentos: no abre los del proyecto.
        assertThat(call("GET", "/api/docs/" + productId, null, dev).status()).isEqualTo(200);
        assertThat(call("GET", "/api/docs/" + productId, null, mkt).status()).isEqualTo(404);
        assertThat(call("GET", "/api/subjects", null, mkt).body().toString()).contains("Gimnasio");
        // Lo del proyecto desde Organización es de quien administra.
        assertThat(call("GET", "/api/subjects/GYM/docs", null, dev).status()).isEqualTo(403);
        assertThat(call("PUT", "/api/subjects/GYM", Map.of("name", "Otro nombre"), dev).status()).isEqualTo(403);
    }

    private List<String> ids(String path, String token) throws Exception {
        List<String> out = new ArrayList<>();
        call("GET", path, null, token).body().get("documents").forEach(d -> out.add(d.get("id").asString()));
        return out;
    }
}
