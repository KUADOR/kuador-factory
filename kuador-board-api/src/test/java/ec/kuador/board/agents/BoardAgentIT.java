package ec.kuador.board.agents;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Un agente que trabaja en el tablero: la empresa pone su clave de IA, el tablero de ventas trae su equipo, la
 * persona adjunta un CSV y le asigna la tarjeta al Analista; el agente (contra una API de Anthropic falsa que
 * «ejecuta código» y genera un PDF) adjunta el informe, comenta y devuelve la tarjeta.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=", "board.agents.anthropic-api-key=",
        "board.sync.allowed-roots=", "board.platform.admins=flor@flores.test",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class BoardAgentIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Container
    static final GenericContainer<?> GARAGE = new GenericContainer<>("dxflrs/garage:v2.5.0")
            .withEnv("GARAGE_RPC_SECRET", "b".repeat(64))
            .withEnv("GARAGE_ADMIN_TOKEN", "token-de-prueba")
            .withCopyToContainer(Transferable.of(read("../infra/garage/garage.toml")), "/etc/garage.toml")
            .withExposedPorts(3900)
            .waitingFor(Wait.forListeningPort());

    static final Map<String, String> INBOX = new ConcurrentHashMap<>();
    static final List<String> MESSAGES = new CopyOnWriteArrayList<>();
    static final List<String> UPLOADS = new CopyOnWriteArrayList<>();
    static final List<String> DELETED = new CopyOnWriteArrayList<>();
    static final byte[] PDF = "%PDF-1.4 informe".getBytes(StandardCharsets.UTF_8);
    static com.sun.net.httpserver.HttpServer anthropic;
    static final List<String> OPENAI_REQUESTS = new CopyOnWriteArrayList<>();
    /** El modelo crea el documento con una cifra que sale de una consulta sobre el CSV, y entrega. */
    static final String DEEPSEEK_REPLY = """
            {"model":"deepseek-prueba","usage":{"prompt_tokens":900,"completion_tokens":300},
             "choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[
               {"id":"c1","type":"function","function":{"name":"create_document","arguments":"{\\"file_name\\":\\"resumen.pdf\\",\\"title\\":\\"Resumen de ventas\\",\\"blocks\\":[{\\"type\\":\\"table\\",\\"sql\\":\\"select sum(monto) as vendido from ventas\\"}]}"}},
               {"id":"c2","type":"function","function":{"name":"deliver","arguments":"{\\"summary\\":\\"Vendiste 100 en dos días. Adjunté el resumen.\\"}"}}
             ]}}]}
            """;

    static {
        try {
            anthropic = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            anthropic.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                String method = ex.getRequestMethod();
                byte[] body = ex.getRequestBody().readAllBytes();
                // El SDK manda la clave como «Authorization: Bearer …» (o x-api-key en otras versiones).
                String auth = ex.getRequestHeaders().getFirst("authorization");
                String key = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : ex.getRequestHeaders().getFirst("x-api-key");
                byte[] out;
                String type = "application/json";
                int status = 200;
                if (!"sk-ant-api03-clave-de-prueba-0123456789".equals(key)) {
                    status = 401;
                    out = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}".getBytes();
                } else if (path.equals("/v1/models")) {
                    out = "{\"data\":[],\"has_more\":false,\"first_id\":null,\"last_id\":null}".getBytes();
                } else if (path.equals("/v1/files") && method.equals("POST")) {
                    UPLOADS.add(new String(body, StandardCharsets.ISO_8859_1));
                    out = file("file_in_1", "ventas.csv", "text/csv", 40);
                } else if (path.equals("/v1/messages")) {
                    MESSAGES.add(new String(body, StandardCharsets.UTF_8));
                    out = message().getBytes(StandardCharsets.UTF_8);
                } else if (path.equals("/v1/files/file_out_1/content")) {
                    out = PDF;
                    type = "application/pdf";
                } else if (path.equals("/v1/files/file_out_1")) {
                    if (method.equals("DELETE")) {
                        DELETED.add("file_out_1");
                        out = "{\"id\":\"file_out_1\",\"type\":\"file_deleted\"}".getBytes();
                    } else {
                        out = file("file_out_1", "informe.pdf", "application/pdf", PDF.length);
                    }
                } else if (path.startsWith("/v1/files/") && method.equals("DELETE")) {
                    DELETED.add(path.substring("/v1/files/".length()));
                    out = ("{\"id\":\"" + path.substring("/v1/files/".length()) + "\",\"type\":\"file_deleted\"}").getBytes();
                } else {
                    status = 404;
                    out = "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"no\"}}".getBytes();
                }
                ex.getResponseHeaders().add("Content-Type", type);
                ex.sendResponseHeaders(status, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            // Un proveedor compatible con OpenAI (como DeepSeek): sin ejecución de código, usa las herramientas del tablero.
            anthropic.createContext("/deepseek/", ex -> {
                boolean ok = "Bearer sk-deepseek-prueba-0123456789".equals(ex.getRequestHeaders().getFirst("authorization"));
                byte[] body = ex.getRequestBody().readAllBytes();
                byte[] out;
                if (!ok) out = "{\"error\":{\"message\":\"Authentication Fails\"}}".getBytes();
                else if (ex.getRequestURI().getPath().endsWith("/models")) out = "{\"object\":\"list\",\"data\":[]}".getBytes();
                else {
                    OPENAI_REQUESTS.add(new String(body, StandardCharsets.UTF_8));
                    out = DEEPSEEK_REPLY.getBytes(StandardCharsets.UTF_8);
                }
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(ok ? 200 : 401, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            anthropic.start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Respuesta del modelo: ejecutó código (dejó informe.pdf en $OUTPUT_DIR) y entregó. */
    static String message() {
        return """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","stop_reason":"tool_use","stop_sequence":null,
             "usage":{"input_tokens":1200,"output_tokens":800},
             "container":{"id":"container_1","expires_at":"2026-12-01T00:00:00Z"},
             "content":[
               {"type":"server_tool_use","id":"srvtoolu_1","name":"bash_code_execution","input":{"command":"python informe.py && cp informe.pdf $OUTPUT_DIR && ls $OUTPUT_DIR"}},
               {"type":"bash_code_execution_tool_result","tool_use_id":"srvtoolu_1",
                "content":{"type":"bash_code_execution_result","stdout":"informe.pdf","stderr":"","return_code":0,
                           "content":[{"type":"bash_code_execution_output","file_id":"file_out_1"}]}},
               {"type":"tool_use","id":"toolu_1","name":"deliver","input":{"summary":"Ventas de octubre: USD 1.250 en 31 días; el mejor día fue el 14."}}
             ]}
            """;
    }

    static byte[] file(String id, String name, String mime, long size) {
        return ("{\"id\":\"" + id + "\",\"type\":\"file\",\"filename\":\"" + name + "\",\"mime_type\":\"" + mime
                + "\",\"size_bytes\":" + size + ",\"created_at\":\"2026-11-12T00:00:00Z\",\"downloadable\":true}").getBytes();
    }

    static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        // Recién arrancado, Garage tarda un momento en generar la clave del nodo.
        String node = null;
        for (int i = 0; i < 50 && node == null; i++) {
            try {
                node = garage("node", "id", "-q").strip().split("@")[0];
            } catch (IllegalStateException e) {
                Thread.sleep(200);
            }
        }
        if (node == null) node = garage("node", "id", "-q").strip().split("@")[0];
        garage("layout", "assign", "-z", "dc1", "-c", "1G", node);
        garage("layout", "apply", "--version", "1");
        garage("bucket", "create", "adjuntos");
        String key = garage("key", "create", "api");
        garage("bucket", "allow", "--read", "--write", "--owner", "adjuntos", "--key", "api");
        r.add("board.storage.s3.endpoint", () -> "http://" + GARAGE.getHost() + ":" + GARAGE.getMappedPort(3900));
        r.add("board.storage.s3.bucket", () -> "adjuntos");
        r.add("board.storage.s3.access-key", () -> value(key, "Key ID:"));
        r.add("board.storage.s3.secret-key", () -> value(key, "Secret key:"));
        r.add("board.agents.anthropic-base-url", () -> "http://127.0.0.1:" + anthropic.getAddress().getPort());
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        r.add("board.secrets-key", () -> java.util.Base64.getEncoder().encodeToString(secret));
    }

    private static String value(String text, String label) {
        return text.lines().filter(l -> l.startsWith(label)).findFirst().orElseThrow().replace(label, "").strip();
    }

    private static String garage(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "/garage";
        System.arraycopy(args, 0, cmd, 1, args.length);
        var result = GARAGE.execInContainer(cmd);
        if (result.getExitCode() != 0) throw new IllegalStateException(String.join(" ", args) + ": " + result.getStderr());
        return result.getStdout();
    }

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
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    record Reply(int status, String text) {
        JsonNode body() {
            return new ObjectMapper().readTree(text);
        }
    }

    private Reply call(String method, String path, Object body, String token) throws Exception {
        var r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) r.header("Authorization", "Bearer " + token);
        HttpResponse<String> res = http.send(r.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(), res.body());
    }

    private Reply upload(String key, String name, String type, byte[] content, String token) throws Exception {
        String boundary = "----kuador" + System.nanoTime();
        var out = new java.io.ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\nContent-Type: " + type + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/issues/" + key + "/attachments"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(), res.body());
    }

    private static void eventually(String what, BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 75 && !condition.getAsBoolean(); i++) Thread.sleep(200);
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    private JsonNode quiet(String method, String path, String token) {
        try {
            return call(method, path, null, token).body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anAnalystOnTheBoardTurnsASpreadsheetIntoAReport() throws Exception {
        signup.requestCode("flor@flores.test");
        signup.complete(signup.verifyCode("flor@flores.test", INBOX.get("flor@flores.test")),
                new SignupService.Company("Flores del Valle", null, null, null, null, null), "Flor", "clave-de-flor-123", "TEAM");
        String owner = auth.login("flor@flores.test", "clave-de-flor-123").token();
        String ownerId = call("GET", "/api/auth/me", null, owner).body().get("id").asString();

        // La clave de IA de la empresa: se comprueba con Anthropic y nunca vuelve completa.
        assertThat(call("PUT", "/api/workspace/ai", Map.of("apiKey", "sk-ant-api03-clave-mala-0123456789000"), owner).body().get("detail").asString())
                .contains("rechazó la clave");
        JsonNode ai = call("PUT", "/api/workspace/ai", Map.of("apiKey", "sk-ant-api03-clave-de-prueba-0123456789", "defaultModel", "claude-opus-5-5"), owner).body();
        assertThat(ai.path("configured").asBoolean(false)).as(ai.toString()).isTrue();
        assertThat(ai.toString()).doesNotContain("clave-de-prueba").contains("…6789");

        // El tablero de ventas trae su equipo: Analista y Marketing.
        call("POST", "/api/projects", Map.of("key", "VEN", "name", "Ventas", "templateKey", "SALES"), owner);
        eventually("equipo por defecto", () -> quiet("GET", "/api/projects/VEN/team", owner).valueStream()
                .filter(a -> a.get("inTeam").asBoolean()).map(a -> a.get("role").asString()).toList().containsAll(List.of("ANALYST", "MARKETING")));
        JsonNode analyst = quiet("GET", "/api/projects/VEN/team", owner).valueStream().filter(a -> a.get("role").asString().equals("ANALYST"))
                .findFirst().orElseThrow();
        assertThat(analyst.get("mode").asString()).as("fuera de software, el agente trabaja en el tablero").isEqualTo("SERVER");
        String analystId = analyst.get("id").asString();
        assertThat(call("PUT", "/api/agents/" + analystId + "/profile", Map.of("mode", "SERVER"), owner).body().get("mode").asString()).isEqualTo("SERVER");

        // La persona adjunta las ventas y le pide el informe.
        String key = call("POST", "/api/projects/VEN/issues", Map.of("title", "Informe de ventas de octubre", "type", "TASK",
                "description", "Informe de ventas diarias del último mes, en PDF."), owner).body().get("key").asString();
        upload(key, "ventas.csv", "text/csv", "fecha,monto\n2026-10-01,40\n".getBytes(StandardCharsets.UTF_8), owner);
        call("PUT", "/api/issues/" + key + "/assignee", Map.of("assigneeId", analystId), owner);

        eventually("el agente entrega el informe", () -> quiet("GET", "/api/issues/" + key + "/attachments", owner).get("items").findValuesAsString("fileName")
                .contains("informe.pdf"));
        eventually("y le devuelve la tarjeta", () -> ownerId.equals(quiet("GET", "/api/issues/" + key, owner).path("assigneeId").asString("")));
        String comments = quiet("GET", "/api/issues/" + key + "/comments", owner).toString();
        assertThat(comments).contains("Ventas de octubre: USD 1.250", "Adjunté: informe.pdf");

        // Lo que se le mandó al modelo: su manual, la tarjeta, el CSV en el entorno y la ejecución de código.
        String request = MESSAGES.getFirst();
        assertThat(request).contains("Analista de datos e informes", "Informe de ventas diarias del último mes", "\"container_upload\"", "file_in_1",
                "code_execution", "\"deliver\"", "\"ask_human\"", "claude-opus-5-5");
        assertThat(UPLOADS.getFirst()).contains("filename=\"ventas.csv\"", "2026-10-01,40");
        JsonNode report = quiet("GET", "/api/issues/" + key + "/attachments", owner).get("items").valueStream()
                .filter(a -> a.get("fileName").asString().equals("informe.pdf")).findFirst().orElseThrow();
        assertThat(report.get("uploadedBy").asString()).isEqualTo(analystId);
        HttpResponse<byte[]> pdf = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/attachments/" + report.get("id").asString() + "/content"))
                .header("Authorization", "Bearer " + owner).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(pdf.body()).isEqualTo(PDF);
        assertThat(DELETED).as("no quedan archivos en Anthropic").contains("file_in_1", "file_out_1");

        // Consumo del mes: con el precio del modelo (lo configura la plataforma), el costo estimado.
        JsonNode usage = call("GET", "/api/workspace/ai/usage", null, owner).body();
        assertThat(usage.get("runs").asLong()).isEqualTo(1);
        assertThat(usage.get("inputTokens").asLong()).isEqualTo(1200);
        assertThat(usage.get("costUsd").isNull()).as("sin precio no hay costo").isTrue();
        assertThat(usage.get("unpricedModels").toString()).contains("claude-opus-5-5");
        call("PUT", "/api/platform/ai-prices/claude-opus-5-5", Map.of("inputPerMTok", 5, "outputPerMTok", 25), owner);
        usage = call("GET", "/api/workspace/ai/usage", null, owner).body();
        assertThat(usage.get("costUsd").decimalValue()).as("1200 × 5 + 800 × 25 por millón").isEqualByComparingTo("0.03");
        assertThat(usage.get("byAgent").get(0).get("name").asString()).isEqualTo(analyst.get("name").asString());

        // Con el tope alcanzado, el agente no arranca y lo dice.
        call("PUT", "/api/workspace/ai/budget", Map.of("budgetUsd", 0.01), owner);
        String capped = call("POST", "/api/projects/VEN/issues", Map.of("title", "Informe con tope", "type", "TASK"), owner).body().get("key").asString();
        call("PUT", "/api/issues/" + capped + "/assignee", Map.of("assigneeId", analystId), owner);
        eventually("avisa el tope", () -> quiet("GET", "/api/issues/" + capped + "/comments", owner).toString().contains("tope mensual"));
        assertThat(MESSAGES).as("no se llamó al modelo").hasSize(1);
        call("PUT", "/api/workspace/ai/budget", java.util.Collections.singletonMap("budgetUsd", null), owner);

        // La empresa usa DeepSeek: la plataforma lo habilita con su dirección y modelos, y la empresa cambia de proveedor.
        String deepseek = "http://127.0.0.1:" + anthropic.getAddress().getPort() + "/deepseek";
        call("PUT", "/api/platform/ai-providers/DEEPSEEK", Map.of("name", "DeepSeek", "protocol", "OPENAI_COMPATIBLE", "baseUrl", deepseek,
                "models", List.of("deepseek-prueba"), "active", true, "sortOrder", 30), owner);
        assertThat(call("PUT", "/api/workspace/ai", Map.of("provider", "DEEPSEEK"), owner).body().get("detail").asString())
                .as("cambiar de proveedor pide su clave").contains("Pega la clave de API de DeepSeek");
        JsonNode switched = call("PUT", "/api/workspace/ai", Map.of("provider", "DEEPSEEK", "apiKey", "sk-deepseek-prueba-0123456789"), owner).body();
        assertThat(switched.get("provider").asString()).as(switched.toString()).isEqualTo("DEEPSEEK");
        assertThat(switched.get("defaultModel").asString()).isEqualTo("deepseek-prueba");
        assertThat(switched.get("providers").findValuesAsString("code")).contains("ANTHROPIC", "DEEPSEEK").doesNotContain("OPENAI");

        String summary = call("POST", "/api/projects/VEN/issues", Map.of("title", "Resumen de ventas", "type", "TASK",
                "description", "Resume lo vendido en un PDF."), owner).body().get("key").asString();
        upload(summary, "ventas.csv", "text/csv", "fecha,monto\n2026-10-01,40\n2026-10-02,60\n".getBytes(StandardCharsets.UTF_8), owner);
        call("PUT", "/api/issues/" + summary + "/assignee", Map.of("assigneeId", analystId), owner);
        eventually("entrega con DeepSeek", () -> quiet("GET", "/api/issues/" + summary + "/attachments", owner).get("items").findValuesAsString("fileName")
                .contains("resumen.pdf"));
        eventually("comenta", () -> quiet("GET", "/api/issues/" + summary + "/comments", owner).toString().contains("Vendiste 100"));
        assertThat(OPENAI_REQUESTS.getFirst()).contains("deepseek-prueba", "Tabla ventas", "query_data", "Resume lo vendido");
        JsonNode made = quiet("GET", "/api/issues/" + summary + "/attachments", owner).get("items").valueStream()
                .filter(a -> a.get("fileName").asString().equals("resumen.pdf")).findFirst().orElseThrow();
        byte[] madePdf = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/attachments/" + made.get("id").asString() + "/content"))
                .header("Authorization", "Bearer " + owner).build(), HttpResponse.BodyHandlers.ofByteArray()).body();
        try (var doc = org.apache.pdfbox.Loader.loadPDF(madePdf)) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(doc)).contains("Resumen de ventas", "vendido", "100");
        }
        assertThat(call("GET", "/api/workspace/ai/usage", null, owner).body().get("unpricedModels").toString()).contains("deepseek-prueba");

        // Sin clave, el agente no empieza y lo dice en la tarjeta.
        call("DELETE", "/api/workspace/ai", null, owner);
        String other = call("POST", "/api/projects/VEN/issues", Map.of("title", "Otro informe", "type", "TASK"), owner).body().get("key").asString();
        call("PUT", "/api/issues/" + other + "/assignee", Map.of("assigneeId", analystId), owner);
        eventually("avisa que falta la clave", () -> quiet("GET", "/api/issues/" + other + "/comments", owner).toString().contains("Conexiones → IA"));
    }
}
