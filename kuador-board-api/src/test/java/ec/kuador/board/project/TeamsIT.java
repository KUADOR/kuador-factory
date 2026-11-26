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
 * Un equipo (personas y agentes) se arma una vez y atiende uno o varios tableros: quien está en el equipo ve sus
 * tableros y recibe su trabajo. Los equipos los arma quien administra.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class TeamsIT {

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

    @Test
    void aNewBoardGetsATeamWithItsNameAndItsCreatorInside() throws Exception {
        String owner = owner("sol@equipo-nuevo.test", "Equipo nuevo");
        JsonNode board = call("POST", "/api/projects", Map.of("key", "VEN", "name", "Ventas", "templateKey", "SALES"), owner).body();
        assertThat(board.get("team").get("name").asString()).isEqualTo("Ventas");

        JsonNode team = call("GET", "/api/teams/" + board.get("team").get("id").asString(), null, owner).body();
        assertThat(team.get("boards").toString()).isEqualTo("[\"VEN\"]");
        assertThat(team.get("members").findValues("name").stream().map(JsonNode::asString)).contains("Sol");
        // Un segundo tablero con el mismo nombre no repite el nombre del equipo.
        JsonNode other = call("POST", "/api/projects", Map.of("key", "VEN2", "name", "Ventas", "templateKey", "SALES"), owner).body();
        assertThat(other.get("team").get("name").asString()).isEqualTo("Ventas (VEN2)");
    }

    @Test
    void oneTeamServesSeveralBoardsAndItsPeopleSeeThemAll() throws Exception {
        String owner = owner("sol@un-equipo.test", "Un equipo");
        String anaId = editor(owner, "ana@un-equipo.test", "Ana");
        String ana = auth.login("ana@un-equipo.test", "clave-de-edu-123").token();

        // Solo quien administra arma equipos.
        assertThat(call("POST", "/api/teams", Map.of("name", "team01"), ana).status()).isEqualTo(403);
        JsonNode team = call("POST", "/api/teams", Map.of("name", "team01", "members", List.of(anaId)), owner).body();
        String teamId = team.get("id").asString();
        assertThat(call("POST", "/api/teams", Map.of("name", "TEAM01"), owner).status()).as("nombre repetido").isEqualTo(409);

        // Dos tableros nacen atendidos por team01: Ana ve los dos.
        call("POST", "/api/projects", Map.of("key", "DEV", "name", "Desarrollo", "templateKey", "TASKS", "teamId", teamId), owner);
        call("POST", "/api/projects", Map.of("key", "SOP", "name", "Soporte", "templateKey", "TASKS", "teamId", teamId), owner);
        call("POST", "/api/projects", Map.of("key", "MKT", "name", "Marketing", "templateKey", "TASKS"), owner);
        assertThat(keys(call("GET", "/api/projects", null, ana).body())).containsExactly("DEV", "SOP");
        assertThat(call("GET", "/api/teams/" + teamId, null, owner).body().get("boards").toString()).isEqualTo("[\"DEV\",\"SOP\"]");

        // Ana ve su equipo, no los demás.
        List<String> names = new ArrayList<>();
        call("GET", "/api/teams", null, ana).body().forEach(t -> names.add(t.get("name").asString()));
        assertThat(names).containsExactly("team01");
        String mktTeam = call("GET", "/api/projects/MKT", null, owner).body().get("team").get("id").asString();
        assertThat(call("GET", "/api/teams/" + mktTeam, null, ana).status()).isEqualTo(404);

        // Se le puede pasar trabajo en los tableros de su equipo.
        String card = call("POST", "/api/projects/SOP/issues", Map.of("title", "Responder", "type", "TASK"), owner).body().get("key").asString();
        assertThat(call("PUT", "/api/issues/" + card + "/assignee", Map.of("assigneeId", anaId), owner).status()).isEqualTo(200);

        // Marketing pasa a team01: Ana lo ve. Sale de team01: no ve ninguno.
        assertThat(call("PUT", "/api/teams/" + teamId + "/boards/MKT", null, ana).status()).isEqualTo(403);
        assertThat(call("PUT", "/api/teams/" + teamId + "/boards/MKT", null, owner).body().get("name").asString()).isEqualTo("team01");
        assertThat(keys(call("GET", "/api/projects", null, ana).body())).containsExactly("DEV", "MKT", "SOP");
        call("DELETE", "/api/teams/" + teamId + "/members/" + anaId, null, owner);
        assertThat(call("GET", "/api/projects", null, ana).body().size()).isZero();

        // Un equipo que atiende tableros no se borra; uno sin tableros, sí.
        assertThat(call("DELETE", "/api/teams/" + teamId, null, owner).status()).isEqualTo(422);
        assertThat(call("DELETE", "/api/teams/" + mktTeam, null, owner).status()).isEqualTo(204);
    }

    @Test
    void whoDoesNotAdministerOnlyStartsBoardsWithTheirOwnTeams() throws Exception {
        String owner = owner("sol@propios.test", "Propios");
        String eduId = editor(owner, "edu@propios.test", "Edu");
        String edu = auth.login("edu@propios.test", "clave-de-edu-123").token();
        String other = call("POST", "/api/teams", Map.of("name", "Otro"), owner).body().get("id").asString();
        String mine = call("POST", "/api/teams", Map.of("name", "Mío", "members", List.of(eduId)), owner).body().get("id").asString();

        assertThat(call("POST", "/api/projects", Map.of("key", "AJE", "name", "Ajeno", "templateKey", "TASKS", "teamId", other), edu).status()).isEqualTo(404);
        assertThat(call("POST", "/api/projects", Map.of("key", "PRO", "name", "Propio", "templateKey", "TASKS", "teamId", mine), edu).status()).isEqualTo(201);
        assertThat(keys(call("GET", "/api/projects", null, edu).body())).containsExactly("PRO");
    }

    @Test
    void aSharedTeamOnlyGetsTheTemplateRolesItLacks() throws Exception {
        String owner = owner("sol@roles.test", "Roles");
        JsonNode first = call("POST", "/api/projects", Map.of("key", "FAB", "name", "Fábrica", "templateKey", "SOFTWARE_FACTORY"), owner).body();
        String teamId = first.get("team").get("id").asString();
        int before = agentsIn(owner, teamId);
        assertThat(before).isPositive();

        call("POST", "/api/projects", Map.of("key", "FAB2", "name", "Fábrica dos", "templateKey", "SOFTWARE_FACTORY", "teamId", teamId), owner);
        Thread.sleep(1500); // el equipo de la plantilla llega después del commit
        assertThat(agentsIn(owner, teamId)).isEqualTo(before);
    }

    @Test
    void agentsAreCreatedInsideATeamAndWorkOnItsBoards() throws Exception {
        String owner = owner("sol@agentes-equipo.test", "Agentes del equipo");
        String teamId = call("POST", "/api/teams", Map.of("name", "Ventas"), owner).body().get("id").asString();
        call("POST", "/api/projects", Map.of("key", "VEN", "name", "Ventas", "templateKey", "TASKS", "teamId", teamId), owner);

        JsonNode agents = call("POST", "/api/teams/" + teamId + "/agents", Map.of("name", "Vera", "role", "ANALYST", "mode", "SERVER"), owner).body();
        JsonNode vera = null;
        for (JsonNode a : agents) if (a.get("name").asString().equals("Vera")) vera = a;
        assertThat(vera.get("inTeam").asBoolean()).isTrue();
        // El tablero ve al agente en su equipo de agentes.
        boolean onBoard = false;
        for (JsonNode a : call("GET", "/api/projects/VEN/team", null, owner).body()) onBoard |= a.get("name").asString().equals("Vera") && a.get("inTeam").asBoolean();
        assertThat(onBoard).isTrue();
        assertThat(call("GET", "/api/teams/" + UUID.randomUUID() + "/agents", null, owner).status()).isEqualTo(404);
    }

    private int agentsIn(String owner, String teamId) throws Exception {
        for (int i = 0; i < 40; i++) {
            int n = 0;
            for (JsonNode m : call("GET", "/api/teams/" + teamId, null, owner).body().get("members")) if (m.get("agent").asBoolean()) n++;
            if (n > 0) return n;
            Thread.sleep(100);
        }
        return 0;
    }

    private static List<String> keys(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.get("key").asString()));
        return out;
    }
}
