package ec.kuador.board.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.member.application.MemberService;
import ec.kuador.board.member.domain.Member;
import ec.kuador.board.member.domain.MemberKind;
import ec.kuador.board.shared.tenant.TenantContext;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import ec.kuador.board.workspace.domain.Workspace;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Épica con tareas del plan: el arquitecto las crea, arrancan en Desarrollo por rol, respetan dependencias y la épica sigue sola. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class EpicTasksIT {

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
    @Autowired MemberService members;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private int ids;

    record Reply(int status, JsonNode body, byte[] raw) {}

    private Reply call(String method, String path, Object body, String token, String... headers) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) req.header("Authorization", "Bearer " + token);
        for (int i = 0; i < headers.length; i += 2) req.header(headers[i], headers[i + 1]);
        HttpResponse<byte[]> res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        String text = new String(res.body());
        boolean isJson = res.headers().firstValue("Content-Type").orElse("").contains("json") && !text.isBlank();
        return new Reply(res.statusCode(), isJson ? json.readTree(text) : null, res.body());
    }

    private Reply rpc(String token, String method, Object params) throws Exception {
        return call("POST", "/mcp", Map.of("jsonrpc", "2.0", "id", ++ids, "method", method, "params", params), token);
    }

    /** Llama una herramienta y devuelve el texto del resultado; {@code error} indica si se espera isError. */
    private JsonNode tool(String token, String name, Map<String, String> args, boolean error) throws Exception {
        JsonNode result = rpc(token, "tools/call", Map.of("name", name, "arguments", args)).body().get("result");
        assertThat(result.get("isError").asBoolean()).as(name + ": " + result.get("content").get(0).get("text").asString()).isEqualTo(error);
        String text = result.get("content").get(0).get("text").asString();
        return error ? json.getNodeFactory().stringNode(text) : json.readTree(text);
    }

    private String person(Workspace w, String name, String email) {
        Member m = TenantContext.call(w.id(), () -> members.create(name, MemberKind.HUMAN, "DEV", null, "#cccccc", null));
        TenantContext.run(w.id(), () -> auth.grantAccess(m.id(), email, "clave-de-prueba-1"));
        return auth.login(email, "clave-de-prueba-1").token();
    }

    private String column(String owner, String key) throws Exception {
        return call("GET", "/api/issues/" + key, null, owner).body().get("status").asString();
    }

    private List<String> queue(String runner) throws Exception {
        List<String> keys = new ArrayList<>();
        tool(runner, "pending_work", Map.of(), false).get("items").forEach(i -> keys.add(i.get("key").asString() + ":" + i.get("agents").get(0).asString()));
        return keys;
    }

    @Test
    void anEpicIsSplitIntoTasksThatRunInParallelByRole() throws Exception {
        signup.requestCode("eva@ep.test");
        signup.complete(signup.verifyCode("eva@ep.test", INBOX.get("eva@ep.test")),
                new SignupService.Company("Ep", null, null, null, null, null), "Eva", "clave-de-eva-123", "FACTORY");
        String owner = auth.login("eva@ep.test", "clave-de-eva-123").token();
        String runner = call("POST", "/api/auth/runner-tokens", Map.of("name", "Laptop"), owner).body().get("token").asString();
        call("POST", "/api/projects", Map.of("key", "EP", "name", "Ep", "templateKey", "SOFTWARE_FACTORY"), owner);
        String epic = call("POST", "/api/projects/EP/issues", Map.of("title", "Esqueleto del sistema", "type", "EPIC", "status", "DESIGN"), owner)
                .body().get("key").asString();

        // El arquitecto toma la épica: sin tareas no la puede entregar a Desarrollo.
        tool(runner, "take_issue", Map.of("key", epic, "agent", "ARCHITECT"), false);
        assertThat(tool(runner, "get_issue", Map.of("key", epic), false).get("epicGuide").asString()).contains("create_tasks");
        assertThat(tool(runner, "deliver", Map.of("key", epic, "agent", "ARCHITECT"), true).asString()).contains("create_tasks");
        String tasks = json.writeValueAsString(List.of(
                Map.of("title", "API de pacientes", "role", "BACKEND", "description", "T001-T004"),
                Map.of("title", "Pantalla de pacientes", "role", "FRONTEND", "dependsOn", List.of(1)),
                Map.of("title", "Pipeline y contenedores", "role", "DEVOPS")));
        assertThat(tool(runner, "create_tasks", Map.of("key", epic, "agent", "ARCHITECT", "tasks", "[{\"title\":\"x\",\"role\":\"NADIE\"}]"), true).asString())
                .contains("rol que no existe");
        JsonNode created = tool(runner, "create_tasks", Map.of("key", epic, "agent", "ARCHITECT", "tasks", tasks), false);
        String back = created.get("tasks").get(0).get("key").asString(), front = created.get("tasks").get(1).get("key").asString(),
                ops = created.get("tasks").get(2).get("key").asString();
        assertThat(column(owner, back)).as("esperan en Backlog hasta que la épica llegue a Desarrollo").isEqualTo("BACKLOG");
        assertThat(queue(runner)).as("todavía no son de nadie").noneMatch(k -> k.startsWith(back) || k.startsWith(ops));

        // Rehacer el plan antes de que arranquen reemplaza las tareas.
        JsonNode again = tool(runner, "create_tasks", Map.of("key", epic, "agent", "ARCHITECT", "tasks", tasks), false);
        assertThat(call("GET", "/api/issues/" + back, null, owner).status()).as("las anteriores se reemplazan").isEqualTo(404);
        String b = again.get("tasks").get(0).get("key").asString(), f = again.get("tasks").get(1).get("key").asString(),
                o = again.get("tasks").get(2).get("key").asString();
        assertThat(f).isNotBlank();

        // Al entregar la épica, el reparto espera a una persona: nada arranca y la épica no es de ningún agente.
        assertThat(tool(runner, "deliver", Map.of("key", epic, "agent", "ARCHITECT", "comment", "Plan listo."), false).get("column").asString()).isEqualTo("DEVELOPMENT");
        assertThat(column(owner, b)).isEqualTo("BACKLOG");
        assertThat(queue(runner)).isEmpty();
        assertThat(call("GET", "/api/issues/" + epic + "/tasks", null, owner).body()).hasSize(3);

        // Pedir cambios devuelve la épica al arquitecto que hizo el reparto; lo rehace y lo entrega otra vez.
        assertThat(call("POST", "/api/issues/" + epic + "/plan", Map.of("approve", false), owner).status()).as("con motivo").isEqualTo(422);
        call("POST", "/api/issues/" + epic + "/plan", Map.of("approve", false, "comment", "Separa el pipeline del despliegue."), owner);
        assertThat(column(owner, epic)).isEqualTo("DESIGN");
        assertThat(queue(runner)).contains(epic + ":ARCHITECT");
        again = tool(runner, "create_tasks", Map.of("key", epic, "agent", "ARCHITECT", "tasks", tasks), false);
        b = again.get("tasks").get(0).get("key").asString();
        f = again.get("tasks").get(1).get("key").asString();
        o = again.get("tasks").get(2).get("key").asString();
        tool(runner, "deliver", Map.of("key", epic, "agent", "ARCHITECT", "comment", "Plan ajustado."), false);

        // Aprobado el reparto, las tareas arrancan en Desarrollo, cada una para su rol; el front espera al backend.
        assertThat(call("POST", "/api/issues/" + epic + "/plan", Map.of("approve", true), owner).status()).isEqualTo(200);
        assertThat(column(owner, b)).isEqualTo("DEVELOPMENT");
        assertThat(column(owner, f)).isEqualTo("DEVELOPMENT");
        assertThat(queue(runner)).containsExactlyInAnyOrder(b + ":BACKEND", o + ":DEVOPS");
        assertThat(tool(runner, "take_issue", Map.of("key", f, "agent", "FRONTEND"), true).asString()).contains("depende de " + b);
        assertThat(tool(runner, "take_issue", Map.of("key", o, "agent", "BACKEND"), true).asString()).contains("es una tarea de");

        // Backend y DevOps trabajan en paralelo, cada uno con su rama y su PR.
        tool(runner, "take_issue", Map.of("key", b, "agent", "BACKEND"), false);
        tool(runner, "take_issue", Map.of("key", o, "agent", "DEVOPS"), false);
        JsonNode task = tool(runner, "get_issue", Map.of("key", b), false).get("task");
        assertThat(task.get("role").asString()).isEqualTo("BACKEND");
        assertThat(task.get("branch").asString()).isEqualTo("feature/" + b + "-api-de-pacientes");
        assertThat(task.get("rules").toString()).contains("worktree");
        assertThat(tool(runner, "get_issue", Map.of("key", epic), false).get("tasks")).hasSize(3);

        // El backend pasa QA y su PR se aprueba: la tarea termina (sin release propio) y libera al front.
        tool(runner, "deliver", Map.of("key", b, "agent", "BACKEND", "comment", "PR #1"), false);
        tool(runner, "take_issue", Map.of("key", b, "agent", "QA"), false);
        tool(runner, "deliver", Map.of("key", b, "agent", "QA", "comment", "Criterios verificados."), false);
        call("POST", "/api/issues/" + b + "/review", Map.of("approve", true), owner);
        assertThat(column(owner, b)).isEqualTo("DONE");
        assertThat(queue(runner)).contains(f + ":FRONTEND");

        // Al terminar todas, la épica pasa a Release (un release por épica).
        for (String[] t : new String[][] {{f, "FRONTEND"}, {o, "DEVOPS"}}) {
            if (!t[1].equals("DEVOPS")) tool(runner, "take_issue", Map.of("key", t[0], "agent", t[1]), false);
            tool(runner, "deliver", Map.of("key", t[0], "agent", t[1]), false);
            tool(runner, "take_issue", Map.of("key", t[0], "agent", "QA"), false);
            tool(runner, "deliver", Map.of("key", t[0], "agent", "QA"), false);
            assertThat(column(owner, epic)).isEqualTo("DEVELOPMENT");
            call("POST", "/api/issues/" + t[0] + "/review", Map.of("approve", true), owner);
        }
        assertThat(column(owner, epic)).isEqualTo("RELEASE");
        List<String> bodies = new ArrayList<>();
        call("GET", "/api/issues/" + epic + "/comments", null, owner).body().forEach(c -> bodies.add(c.get("body").asString()));
        assertThat(bodies).anyMatch(x -> x.contains("Reparto por aprobar")).anyMatch(x -> x.contains("Reparto aprobado")).anyMatch(x -> x.contains("Arrancan 3 tarea(s)")).anyMatch(x -> x.contains("Terminaron todas sus tareas (3)"));
    }
}
