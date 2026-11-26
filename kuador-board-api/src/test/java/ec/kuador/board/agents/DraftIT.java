package ec.kuador.board.agents;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Redactar los documentos base «en mi computador»: el tablero arma el pedido, el runner de la persona lo toma y devuelve
 * el texto, y la web ve las preguntas, los borradores y, al aprobar, los documentos y el objetivo guardados.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class DraftIT {

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

    /** El runner toma el trabajo y devuelve lo que habría respondido Claude Code. */
    private String runnerAnswers(String runner, UUID machine, String text) throws Exception {
        Reply claimed = call("POST", "/api/runner/jobs/claim", Map.of("machineId", machine), runner);
        assertThat(claimed.status()).isEqualTo(200);
        assertThat(claimed.body().get("system").asString()).contains("JSON");
        String id = claimed.body().get("id").asString();
        assertThat(call("POST", "/api/runner/jobs/" + id + "/complete", Map.of("machineId", machine, "text", text), runner).status()).isEqualTo(204);
        return claimed.body().get("user").asString();
    }

    @Test
    void theRunnerDraftsTheBaseDocumentsAndTheBoardGoalWithThePersonsSubscription() throws Exception {
        signup.requestCode("ines@draft.test");
        signup.complete(signup.verifyCode("ines@draft.test", INBOX.get("ines@draft.test")),
                new SignupService.Company("Draft", null, null, null, null, null), "Inés", "clave-de-ines-123", "FACTORY");
        String owner = auth.login("ines@draft.test", "clave-de-ines-123").token();
        String runner = call("POST", "/api/auth/runner-tokens", Map.of("name", "Mac de Inés"), owner).body().get("token").asString();
        UUID machine = UUID.randomUUID();
        // Una conexión creada a mano también es un runner: registra su máquina.
        call("POST", "/api/projects", Map.of("key", "CLI", "name", "Clínica", "templateKey", "SOFTWARE_FACTORY"), owner);
        call("PUT", "/api/projects/CLI/methodology", Map.of("methodology", "ODD"), owner);

        // En ODD lo mínimo es Producto y Reglas para agentes; lo demás, opcional.
        JsonNode plan = call("GET", "/api/projects/CLI/drafting", null, owner).body();
        assertThat(plan.get("serverReady").asBoolean()).as("sin clave de IA").isFalse();
        List<String> suggested = new java.util.ArrayList<>();
        plan.get("documents").forEach(d -> { if (d.get("suggested").asBoolean()) suggested.add(d.get("kind").asString()); });
        assertThat(suggested).containsExactlyInAnyOrder("PRODUCT", "AGENT_RULES");
        assertThat(plan.get("pendingBase")).as("lo mínimo de ODD, sin redactar todavía").hasSize(2);

        Map<String, Object> idea = Map.of("idea", "Agenda de citas en línea para la clínica, simple para pacientes mayores.");
        // Sin clave de IA, el servidor dice que se use la máquina.
        assertThat(call("POST", "/api/projects/CLI/drafting/questions", idea, owner).body().get("detail").asString()).contains("En mi computador");
        assertThat(call("POST", "/api/projects/CLI/drafting/questions?local=true", Map.of("idea", " "), owner).status()).isEqualTo(422);

        // 1. Preguntas: la web deja el trabajo; el runner lo resuelve (la respuesta trae un bloque de código: igual se entiende).
        assertThat(call("PUT", "/api/runner/heartbeat", Map.of("machineId", machine, "name", "Mac", "os", "macos", "tool", "claude",
                "roles", List.of("BACKEND"), "capacity", 1, "register", true), runner).status()).isEqualTo(200);
        String job = call("POST", "/api/projects/CLI/drafting/questions?local=true", idea, owner).body().get("id").asString();
        assertThat(call("GET", "/api/drafting/jobs/" + job, null, owner).body().get("status").asString()).isEqualTo("PENDING");
        String asked = runnerAnswers(runner, machine, "```json\n{\"questions\":[{\"id\":\"q1\",\"text\":\"¿Quiénes la usan?\",\"options\":[\"Pacientes\",\"Recepción\"]}]}\n```");
        assertThat(asked).contains("pacientes mayores");
        JsonNode questions = call("GET", "/api/drafting/jobs/" + job, null, owner).body();
        assertThat(questions.get("status").asString()).isEqualTo("DONE");
        assertThat(questions.get("result").get("questions").get(0).get("options").size()).isEqualTo(2);
        assertThat(call("POST", "/api/runner/jobs/claim", Map.of("machineId", machine), runner).status()).as("ya no hay más").isEqualTo(204);

        // 2. Borradores de lo elegido, con la plantilla de la metodología, y el objetivo a partir del producto.
        String draft = call("POST", "/api/projects/CLI/drafting/draft?local=true",
                Map.of("idea", idea.get("idea"), "answers", Map.of("¿Quiénes la usan?", "Pacientes"), "kinds", List.of("PRODUCT", "AGENT_RULES")), owner)
                .body().get("id").asString();
        String drafted = runnerAnswers(runner, machine, """
                {"documents":[{"kind":"PRODUCT","content":"# Producto\\n\\nAgenda para pacientes mayores."},{"kind":"AGENT_RULES","content":"# Reglas\\n\\n- Letra grande."},
                 {"kind":"INVENTADO","content":"no va"}],"goal":"Que los pacientes mayores agenden solos en línea."}""");
        assertThat(drafted).contains("PRODUCT", "AGENT_RULES", "Plantilla").doesNotContain("### GLOSSARY");
        JsonNode docs = call("GET", "/api/drafting/jobs/" + draft, null, owner).body().get("result");
        assertThat(docs.get("documents").size()).as("lo que no pide la metodología se descarta").isEqualTo(2);
        assertThat(docs.get("goal").asString()).contains("pacientes mayores");

        // 3. Aprobar: quedan como documentos del proyecto y el objetivo del tablero.
        JsonNode approved = call("POST", "/api/projects/CLI/drafting/approve", Map.of("documents", List.of(
                Map.of("kind", "PRODUCT", "content", docs.get("documents").get(0).get("content").asString()),
                Map.of("kind", "AGENT_RULES", "content", docs.get("documents").get(1).get("content").asString())),
                "goal", docs.get("goal").asString()), owner).body();
        assertThat(approved.get("saved").toString()).contains("PRODUCT", "AGENT_RULES");
        assertThat(call("GET", "/api/projects/CLI/goal", null, owner).body().get("goal").asString()).contains("pacientes mayores");
        assertThat(call("GET", "/api/projects/CLI/docs", null, owner).body().toString()).contains("PRODUCT", "AGENT_RULES");
        assertThat(call("GET", "/api/projects/CLI/drafting", null, owner).body().get("pendingBase")).as("ya puede crear el repo").isEmpty();

        // Un proyecto que ya existe: se vincula su repo y el reconocimiento (en mi computador) arma piezas, documentos,
        // objetivo, diagnóstico y tarjetas sugeridas; al aprobar quedan las piezas, las tarjetas elegidas y los documentos.
        call("POST", "/api/projects", Map.of("key", "OLD", "name", "Sistema actual", "templateKey", "SOFTWARE_FACTORY"), owner);
        call("PUT", "/api/projects/OLD/methodology", Map.of("methodology", "ODD"), owner);
        assertThat(call("POST", "/api/projects/OLD/drafting/recognize?local=true", Map.of("idea", ""), owner).body().get("detail").asString())
                .as("sin repo vinculado").contains("vincula el repositorio");
        jdbc.sql("""
                insert into project_repository (id, workspace_id, project_key, subject_key, kind, location, branch, secret, linked_at)
                select gen_random_uuid(), workspace_id, 'OLD', 'OLD', 'GITHUB_APP', 'acme/sistema', 'main', '123', now() from project where project_key = 'OLD'""").update();
        assertThat(call("GET", "/api/projects/OLD/drafting", null, owner).body().get("repositories").toString()).contains("acme/sistema");
        String rec = call("POST", "/api/projects/OLD/drafting/recognize?local=true", Map.of("idea", "Sumar recordatorios por WhatsApp", "kinds", List.of("PRODUCT", "AGENT_RULES")),
                owner).body().get("id").asString();
        Reply claimedRec = call("POST", "/api/runner/jobs/claim", Map.of("machineId", machine), runner);
        assertThat(claimedRec.body().get("step").asString()).isEqualTo("RECOGNIZE");
        assertThat(claimedRec.body().get("projectKey").asString()).as("el runner trabaja en la copia de ese repo").isEqualTo("OLD");
        assertThat(claimedRec.body().get("user").asString()).contains("SOLO leyendo", "acme/sistema", "Sumar recordatorios");
        call("POST", "/api/runner/jobs/" + claimedRec.body().get("id").asString() + "/complete", Map.of("machineId", machine, "text", """
                {"pieces":[{"name":"api","kind":"BACKEND","repository":"acme/sistema","path":"api","stack":"Java 21 · Spring Boot 3.4","build":"./mvnw package",
                "test":"./mvnw test","run":"./mvnw spring-boot:run","notes":"Usa PostgreSQL."},{"name":"Web App","kind":"FRONTEND","path":"web","stack":"Angular 19",
                "build":"npm run build","test":"npm test","run":"npm start","notes":""}],"documents":[{"kind":"PRODUCT","content":"# Producto\\n\\nSistema de citas."}],
                "goal":"Mantener el sistema de citas y sumar recordatorios.","findings":["La web no tiene pruebas."],
                "cards":[{"title":"Agregar pruebas a la web","description":"Cubrir el flujo de agenda."},{"title":"Actualizar Angular","description":"De 17 a 19."}]}"""),
                runner);
        JsonNode recJob = call("GET", "/api/drafting/jobs/" + rec, null, owner).body();
        assertThat(recJob.get("status").asString()).as(recJob.toString()).isEqualTo("DONE");
        JsonNode recognized = recJob.get("result");
        assertThat(recognized.get("pieces").size()).isEqualTo(2);
        assertThat(recognized.get("findings").get(0).asString()).contains("pruebas");
        assertThat(recognized.get("cards").size()).isEqualTo(2);
        JsonNode done = call("POST", "/api/projects/OLD/drafting/approve", Map.of(
                "documents", List.of(Map.of("kind", "PRODUCT", "content", "# Producto\n\nSistema de citas.")),
                "goal", recognized.get("goal").asString(),
                "pieces", List.of(Map.of("name", "api", "kind", "BACKEND", "repository", "acme/sistema", "path", "api", "stack", "Java 21 · Spring Boot 3.4",
                        "build", "./mvnw package", "test", "./mvnw test", "run", "./mvnw spring-boot:run", "notes", "Usa PostgreSQL."),
                        Map.of("name", "Web App", "kind", "FRONTEND", "path", "web", "stack", "Angular 19", "build", "npm run build", "test", "npm test", "run", "npm start")),
                "cards", List.of(Map.of("title", "Agregar pruebas a la web", "description", "Cubrir el flujo de agenda.")),
                "kitPullRequest", true), owner).body();
        assertThat(done.get("kitNote").asString()).as("sin la app de GitHub no hay PR, pero lo aprobado queda").isNotBlank();
        assertThat(done.get("pieces").asInt()).isEqualTo(2);
        assertThat(done.get("cards").size()).as("solo la que eligió").isEqualTo(1);
        JsonNode pieces = call("GET", "/api/projects/OLD/components", null, owner).body();
        assertThat(pieces.toString()).contains("web-app", "Angular 19", "Java 21").doesNotContain("Web App\"");
        // Las piezas reconocidas son el stack: Arquitectura (creada desde su plantilla) lo trae escrito por el tablero.
        JsonNode oldDocs = call("GET", "/api/projects/OLD/docs", null, owner).body();
        String architectureId = oldDocs.get("documents").valueStream().filter(d -> d.get("kind").asString().equals("ARCHITECTURE")).findFirst().orElseThrow()
                .get("id").asString();
        assertThat(call("GET", "/api/docs/" + architectureId, null, owner).body().get("content").asString())
                .contains("## Stack", "| api | Backend | Java 21 · Spring Boot 3.4 | `api/` |", "| web-app | Frontend | Angular 19 |");
        JsonNode card = call("GET", "/api/issues/" + done.get("cards").get(0).asString(), null, owner).body();
        assertThat(card.get("labels").toString()).contains("sugerida");

        // Fuera de software: solo el objetivo del tablero.
        call("POST", "/api/projects", Map.of("key", "MKT", "name", "Marketing", "templateKey", "TASKS"), owner);
        assertThat(call("GET", "/api/projects/MKT/drafting", null, owner).body().get("documents").size()).isZero();
        String goalJob = call("POST", "/api/projects/MKT/drafting/draft?local=true", Map.of("idea", "Publicar 3 veces por semana para captar pymes."), owner)
                .body().get("id").asString();
        assertThat(runnerAnswers(runner, machine, "{\"goal\":\"Captar pymes con 3 publicaciones por semana.\"}")).contains("objetivo del tablero");
        assertThat(call("GET", "/api/drafting/jobs/" + goalJob, null, owner).body().get("result").get("goal").asString()).contains("pymes");
    }
}
