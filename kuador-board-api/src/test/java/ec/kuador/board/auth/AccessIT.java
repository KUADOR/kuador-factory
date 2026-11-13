package ec.kuador.board.auth;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.member.MemberAccess;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Acceso de solo lectura por HTTP: un cliente mira el tablero y comenta, no puede cambiar nada y sí
 * cierra las puertas donde su rol es aprobador.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=", "board.agents.anthropic-api-key=",
        "board.sync.allowed-roots=", "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class AccessIT {

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
        return new Reply(res.statusCode(), res.body().isBlank() ? null : json.readTree(res.body()));
    }

    @Test
    void aViewOnlyClientWatchesAndOnlyClosesItsGates() throws Exception {
        signup.requestCode("olga@omega.test");
        String signupToken = signup.verifyCode("olga@omega.test", INBOX.get("olga@omega.test"));
        Workspace w = signup.complete(signupToken, new SignupService.Company("Omega", null, null, null, null, null), "Olga", "clave-de-olga-123", "FACTORY");
        String owner = auth.login("olga@omega.test", "clave-de-olga-123").token();

        // Un cliente nuevo queda en solo lectura por defecto.
        Member client = TenantContext.call(w.id(), () -> members.create("Cliente Omega", MemberKind.HUMAN, "CLIENTE", null, "#cccccc", null));
        assertThat(client.access()).isEqualTo(MemberAccess.VIEW);
        TenantContext.run(w.id(), () -> auth.grantAccess(client.id(), "cliente@omega.test", "clave-del-cliente-1"));
        String viewer = auth.login("cliente@omega.test", "clave-del-cliente-1").token();

        assertThat(call("POST", "/api/projects", Map.of("key", "OME", "name", "Omega", "templateKey", "SOFTWARE_FACTORY"), owner).status()).isEqualTo(201);
        assertThat(call("POST", "/api/projects/OME/people", Map.of("memberId", client.id().toString()), owner).status()).isEqualTo(200);
        var stages = call("GET", "/api/projects/OME", null, owner).body().get("stages");
        var withClient = json.createArrayNode();
        stages.forEach(s -> {
            var copy = (tools.jackson.databind.node.ObjectNode) s.deepCopy();
            if (s.get("column").asText().equals("APPROVAL")) copy.putArray("approvers").add("OWNER").add("CLIENTE");
            withClient.add(copy);
        });
        assertThat(call("PUT", "/api/projects/OME/stages", Map.of("stages", withClient, "maxRework", 3), owner).status()).isEqualTo(200);
        String gated = call("POST", "/api/projects/OME/issues", Map.of("title", "Agenda", "type", "STORY", "status", "APPROVAL"), owner).body().get("key").asText();
        String other = call("POST", "/api/projects/OME/issues", Map.of("title", "Pagos", "type", "STORY", "status", "DEVELOPMENT"), owner).body().get("key").asText();

        // Mira todo.
        assertThat(call("GET", "/api/auth/me", null, viewer).body().get("access").asText()).isEqualTo("VIEW");
        assertThat(call("GET", "/api/projects/OME/issues", null, viewer).status()).isEqualTo(200);
        assertThat(call("GET", "/api/issues/" + other + "/comments", null, viewer).status()).isEqualTo(200);

        // No escribe.
        Reply create = call("POST", "/api/projects/OME/issues", Map.of("title", "Intruso", "type", "TASK"), viewer);
        assertThat(create.status()).isEqualTo(403);
        assertThat(create.body().get("detail").asText()).contains("solo lectura");
        assertThat(call("POST", "/api/issues/" + other + "/comments", Map.of("body", "¿Esto incluye pagos con tarjeta?"), viewer).status())
                .as("comentar sí puede").isEqualTo(201);
        assertThat(call("POST", "/api/auth/runner-tokens", Map.of("name", "PC"), viewer).status()).as("no pone agentes a trabajar").isEqualTo(403);
        assertThat(call("POST", "/api/issues/" + other + "/move", Map.of("status", "QA", "position", 0), viewer).status()).isEqualTo(403);
        assertThat(call("POST", "/api/issues/" + other + "/take", null, viewer).status()).isEqualTo(403);
        assertThat(call("PUT", "/api/projects/OME/stages", Map.of("stages", withClient, "maxRework", 3), viewer).status()).isEqualTo(403);
        assertThat(call("PUT", "/api/members/" + client.id() + "/access", Map.of("access", "EDIT"), viewer).status()).isEqualTo(403);

        // Pero cierra la puerta donde su rol aprueba, con su comentario.
        Reply approve = call("POST", "/api/issues/" + gated + "/review", Map.of("approve", true, "comment", "De acuerdo"), viewer);
        assertThat(approve.status()).isEqualTo(200);
        assertThat(approve.body().get("status").asText()).isEqualTo("DESIGN");
        assertThat(call("POST", "/api/auth/logout", null, viewer).status()).isEqualTo(204);

        // Quien edita da o quita el acceso; nadie cambia el suyo y siempre queda alguien que edita.
        UUIDs ids = new UUIDs(call("GET", "/api/auth/me", null, owner).body().get("id").asText(), client.id().toString());
        assertThat(call("PUT", "/api/members/" + ids.client() + "/access", Map.of("access", "EDIT"), owner).body().get("access").asText()).isEqualTo("EDIT");
        assertThat(call("PUT", "/api/members/" + ids.owner() + "/access", Map.of("access", "VIEW"), owner).status()).as("nadie cambia el suyo").isEqualTo(422);
    }

    record UUIDs(String owner, String client) {}

    @Test
    void onlyAdminsManageTheTeamAndOnlyOwnersTouchAdminsAndThePlan() throws Exception {
        signup.requestCode("rosa@niveles.test");
        Workspace w = signup.complete(signup.verifyCode("rosa@niveles.test", INBOX.get("rosa@niveles.test")),
                new SignupService.Company("Niveles", null, null, null, null, null), "Rosa", "clave-de-rosa-123", "FACTORY");
        String owner = auth.login("rosa@niveles.test", "clave-de-rosa-123").token();
        assertThat(call("GET", "/api/auth/me", null, owner).body().get("access").asText()).as("quien crea el espacio es dueña").isEqualTo("OWNER");
        String ownerId = call("GET", "/api/auth/me", null, owner).body().get("id").asText();

        // La dueña suma una administradora y una persona que edita (el nivel por defecto: nadie nace administrador).
        JsonNode ada = call("POST", "/api/members", Map.of("name", "Ada", "kind", "HUMAN", "role", "PM", "avatarColor", "#cccccc", "access", "ADMIN"), owner).body();
        JsonNode edu = call("POST", "/api/members", Map.of("name", "Edu", "kind", "HUMAN", "role", "DEV", "avatarColor", "#cccccc"), owner).body();
        assertThat(ada.get("access").asText()).isEqualTo("ADMIN");
        assertThat(edu.get("access").asText()).isEqualTo("EDIT");
        assertThat(call("PUT", "/api/members/" + ada.get("id").asText() + "/credentials", Map.of("email", "ada@niveles.test", "password", "clave-de-ada-123"), owner).status()).isEqualTo(200);
        assertThat(call("PUT", "/api/members/" + edu.get("id").asText() + "/credentials", Map.of("email", "edu@niveles.test", "password", "clave-de-edu-123"), owner).status()).isEqualTo(200);
        String admin = auth.login("ada@niveles.test", "clave-de-ada-123").token();
        String editor = auth.login("edu@niveles.test", "clave-de-edu-123").token();

        // Quien edita trabaja en los tableros, pero no administra el espacio.
        assertThat(call("POST", "/api/projects", Map.of("key", "NIV", "name", "Niveles", "templateKey", "SOFTWARE_FACTORY"), editor).status()).isEqualTo(201);
        assertThat(call("POST", "/api/projects/NIV/issues", Map.of("title", "Tarea", "type", "TASK"), editor).status()).isEqualTo(201);
        Reply invite = call("GET", "/api/invitations", null, editor);
        assertThat(invite.status()).isEqualTo(403);
        assertThat(invite.body().get("detail").asText()).contains("administra");
        assertThat(call("POST", "/api/members", Map.of("name", "Intrusa", "kind", "HUMAN", "role", "DEV", "avatarColor", "#cccccc"), editor).status()).isEqualTo(403);
        assertThat(call("PUT", "/api/members/" + ownerId + "/access", Map.of("access", "VIEW"), editor).status()).as("no baja a la dueña").isEqualTo(403);
        assertThat(call("GET", "/api/workspace/audit", null, editor).status()).isEqualTo(403);
        assertThat(call("GET", "/api/workspace/plan", null, editor).status()).isEqualTo(403);
        assertThat(call("PUT", "/api/workspace", Map.of("name", "Mío"), editor).status()).isEqualTo(403);
        assertThat(call("DELETE", "/api/projects/NIV?confirm=NIV", null, editor).status()).as("borrar un tablero es de quien administra").isEqualTo(403);

        // La administradora gestiona a quienes editan o miran, pero no a otros administradores, ni a la dueña, ni el plan.
        assertThat(call("GET", "/api/workspace/audit", null, admin).status()).isEqualTo(200);
        assertThat(call("GET", "/api/workspace/plan", null, admin).status()).as("ve los límites del plan").isEqualTo(200);
        Reply plan = call("PUT", "/api/workspace/plan", Map.of("plan", "TEAM"), admin);
        assertThat(plan.status()).as("cambiar el plan es de la dueña").isEqualTo(403);
        assertThat(plan.body().get("detail").asText()).contains("dueño");
        assertThat(call("PUT", "/api/members/" + edu.get("id").asText() + "/access", Map.of("access", "VIEW"), admin).body().get("access").asText()).isEqualTo("VIEW");
        assertThat(call("PUT", "/api/members/" + edu.get("id").asText() + "/access", Map.of("access", "ADMIN"), admin).status()).as("no nombra administradores").isEqualTo(403);
        assertThat(call("PUT", "/api/members/" + ownerId + "/access", Map.of("access", "VIEW"), admin).status()).as("no toca a la dueña").isEqualTo(403);
        assertThat(call("POST", "/api/members", Map.of("name", "Otra", "kind", "HUMAN", "role", "PM", "avatarColor", "#cccccc", "access", "ADMIN"), admin).status()).isEqualTo(403);
        JsonNode newAdmin = call("POST", "/api/members", Map.of("name", "Sin cuenta", "kind", "HUMAN", "role", "PM", "avatarColor", "#cccccc", "access", "ADMIN"), owner).body();
        assertThat(call("PUT", "/api/members/" + newAdmin.get("id").asText() + "/credentials", Map.of("email", "toma@niveles.test", "password", "clave-de-toma-123"), admin).status())
                .as("no se queda con la cuenta de un administrador").isEqualTo(403);
        assertThat(call("DELETE", "/api/projects/NIV?confirm=NIV", null, admin).status()).isEqualTo(204);

        // La dueña nombra otra dueña; nunca queda el espacio sin dueño y nadie cambia el suyo.
        assertThat(call("PUT", "/api/members/" + ownerId + "/access", Map.of("access", "ADMIN"), owner).status()).isEqualTo(422);
        assertThat(call("PUT", "/api/members/" + ada.get("id").asText() + "/access", Map.of("access", "OWNER"), owner).body().get("access").asText()).isEqualTo("OWNER");
        assertThat(call("PUT", "/api/members/" + ownerId + "/access", Map.of("access", "ADMIN"), admin).body().get("access").asText()).isEqualTo("ADMIN");
        assertThat(call("PUT", "/api/members/" + ada.get("id").asText() + "/access", Map.of("access", "EDIT"), owner).status())
                .as("Rosa ya no es dueña: no toca a Ada").isEqualTo(403);
        assertThat(w.id()).isNotNull();
    }

    @Test
    void peopleWhoEditOrWatchOnlySeeTheBoardsTheyAreIn() throws Exception {
        signup.requestCode("tere@tableros.test");
        signup.complete(signup.verifyCode("tere@tableros.test", INBOX.get("tere@tableros.test")),
                new SignupService.Company("Tableros", null, null, null, null, null), "Tere", "clave-de-tere-123", "FACTORY");
        String owner = auth.login("tere@tableros.test", "clave-de-tere-123").token();
        for (String key : new String[] {"ALFA", "BETA"}) {
            assertThat(call("POST", "/api/projects", Map.of("key", key, "name", key, "templateKey", "SOFTWARE_FACTORY"), owner).status()).isEqualTo(201);
        }
        String alfaIssue = call("POST", "/api/projects/ALFA/issues", Map.of("title", "De Alfa", "type", "TASK"), owner).body().get("key").asText();
        String betaIssue = call("POST", "/api/projects/BETA/issues", Map.of("title", "De Beta", "type", "TASK"), owner).body().get("key").asText();

        JsonNode edu = call("POST", "/api/members", Map.of("name", "Edu", "kind", "HUMAN", "role", "DEV", "avatarColor", "#cccccc"), owner).body();
        String eduId = edu.get("id").asText();
        call("PUT", "/api/members/" + eduId + "/credentials", Map.of("email", "edu@tableros.test", "password", "clave-de-edu-123"), owner);
        String editor = auth.login("edu@tableros.test", "clave-de-edu-123").token();

        // Recién llegado: no ve ningún tablero, ni por su clave, ni sus issues.
        assertThat(call("GET", "/api/projects", null, editor).body().size()).isZero();
        assertThat(call("GET", "/api/projects/ALFA", null, editor).status()).isEqualTo(404);
        assertThat(call("GET", "/api/projects/ALFA/issues", null, editor).status()).isEqualTo(404);
        assertThat(call("GET", "/api/issues/" + alfaIssue, null, editor).status()).isEqualTo(404);
        assertThat(call("POST", "/api/issues/" + alfaIssue + "/comments", Map.of("body", "hola"), editor).status()).isEqualTo(404);
        assertThat(call("POST", "/api/projects/ALFA/people", Map.of("memberId", eduId), editor).status()).as("no se suma solo").isEqualTo(403);

        // Quien administra lo suma a Alfa: ve Alfa y nada de Beta.
        JsonNode people = call("POST", "/api/projects/ALFA/people", Map.of("memberId", eduId), owner).body();
        assertThat(people.findValues("name").stream().map(JsonNode::asText)).contains("Edu");
        assertThat(keys(call("GET", "/api/projects", null, editor).body())).containsExactly("ALFA");
        assertThat(call("GET", "/api/issues/" + alfaIssue, null, editor).status()).isEqualTo(200);
        assertThat(call("GET", "/api/projects/ALFA/people", null, editor).status()).isEqualTo(200);
        assertThat(call("GET", "/api/issues/" + betaIssue, null, editor).status()).isEqualTo(404);
        assertThat(call("GET", "/api/projects/BETA/docs", null, editor).status()).isEqualTo(404);

        // No se le pasa trabajo de un tablero donde no está.
        Reply assign = call("PUT", "/api/issues/" + betaIssue + "/assignee", Map.of("assigneeId", eduId), owner);
        assertThat(assign.status()).isEqualTo(422);
        assertThat(assign.body().get("detail").asText()).contains("no está en el tablero");
        assertThat(call("PUT", "/api/issues/" + alfaIssue + "/assignee", Map.of("assigneeId", eduId), owner).status()).isEqualTo(200);

        // Quien crea un tablero queda en él.
        assertThat(call("POST", "/api/projects", Map.of("key", "GAMA", "name", "Gama", "templateKey", "SOFTWARE_FACTORY"), editor).status()).isEqualTo(201);
        assertThat(keys(call("GET", "/api/projects", null, editor).body())).containsExactlyInAnyOrder("ALFA", "GAMA");

        // Sale de Alfa y deja de verlo; a quien administra no se lo quita: ve todos por su nivel.
        call("DELETE", "/api/projects/ALFA/people/" + eduId, null, owner);
        assertThat(call("GET", "/api/issues/" + alfaIssue, null, editor).status()).isEqualTo(404);
        String ownerId = call("GET", "/api/auth/me", null, owner).body().get("id").asText();
        assertThat(call("DELETE", "/api/projects/BETA/people/" + ownerId, null, owner).status()).isEqualTo(422);
        assertThat(call("GET", "/api/projects", null, owner).body().size()).isEqualTo(3);
    }

    /** Las claves de los tableros de una lista (sin las de sus columnas). */
    private static java.util.List<String> keys(JsonNode projects) {
        var out = new java.util.ArrayList<String>();
        projects.forEach(p -> out.add(p.get("key").asText()));
        return out;
    }
}
