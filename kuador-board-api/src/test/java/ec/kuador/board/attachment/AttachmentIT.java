package ec.kuador.board.attachment;

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

/** Adjuntos contra un Garage real (el mismo garage.toml de infra/): subir, listar, descargar, leer como agente y borrar. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=", "board.agents.anthropic-api-key=",
        "board.sync.allowed-roots=", "board.storage.max-file-size=1MB",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class AttachmentIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Container
    static final GenericContainer<?> GARAGE = new GenericContainer<>("dxflrs/garage:v2.5.0")
            .withEnv("GARAGE_RPC_SECRET", "a".repeat(64))
            .withEnv("GARAGE_ADMIN_TOKEN", "token-de-prueba")
            .withCopyToContainer(Transferable.of(readConfig()), "/etc/garage.toml")
            .withExposedPorts(3900)
            .waitingFor(Wait.forListeningPort());

    static final Map<String, String> INBOX = new ConcurrentHashMap<>();

    private static String readConfig() {
        try {
            return Files.readString(Path.of("../infra/garage/garage.toml"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lo mismo que hace infra/garage/setup.sh: nodo, bucket y una clave con acceso. */
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry r) throws Exception {
        String node = garage("node", "id", "-q").strip().split("@")[0];
        garage("layout", "assign", "-z", "dc1", "-c", "1G", node);
        garage("layout", "apply", "--version", "1");
        garage("bucket", "create", "adjuntos-prueba");
        String key = garage("key", "create", "prueba");
        String id = key.lines().filter(l -> l.startsWith("Key ID:")).findFirst().orElseThrow().replace("Key ID:", "").strip();
        String secret = key.lines().filter(l -> l.startsWith("Secret key:")).findFirst().orElseThrow().replace("Secret key:", "").strip();
        garage("bucket", "allow", "--read", "--write", "--owner", "adjuntos-prueba", "--key", "prueba");
        r.add("board.storage.s3.endpoint", () -> "http://" + GARAGE.getHost() + ":" + GARAGE.getMappedPort(3900));
        r.add("board.storage.s3.bucket", () -> "adjuntos-prueba");
        r.add("board.storage.s3.access-key", () -> id);
        r.add("board.storage.s3.secret-key", () -> secret);
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

    record Reply(int status, String text, HttpResponse<byte[]> raw) {
        JsonNode body() {
            return new ObjectMapper().readTree(text);
        }
    }

    private Reply call(String method, String path, Object body, String token) throws Exception {
        var r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) r.header("Authorization", "Bearer " + token);
        return send(r);
    }

    private Reply upload(String key, String name, String type, byte[] content, String token) throws Exception {
        String boundary = "----kuador" + System.nanoTime();
        var out = new java.io.ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\nContent-Type: " + type + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/issues/" + key + "/attachments"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())));
    }

    private Reply send(HttpRequest.Builder r) throws Exception {
        HttpResponse<byte[]> res = http.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Reply(res.statusCode(), new String(res.body(), StandardCharsets.UTF_8), res);
    }

    @Test
    void peopleAttachFilesToCardsAndAgentsReadTheTextOnes() throws Exception {
        signup.requestCode("ada@adj.test");
        signup.complete(signup.verifyCode("ada@adj.test", INBOX.get("ada@adj.test")),
                new SignupService.Company("Adjuntos", null, null, null, null, null), "Ada", "clave-de-ada-123", "FACTORY");
        String owner = auth.login("ada@adj.test", "clave-de-ada-123").token();
        call("POST", "/api/projects", Map.of("key", "VEN", "name", "Ventas", "templateKey", "SALES"), owner);
        String key = call("POST", "/api/projects/VEN/issues", Map.of("title", "Clínica Norte", "type", "TASK"), owner).body().get("key").asString();

        Reply proposal = upload(key, "../Propuesta \"final\".md", "text/markdown", "# Propuesta\nPlan anual: $348".getBytes(StandardCharsets.UTF_8), owner);
        assertThat(proposal.status()).isEqualTo(201);
        assertThat(proposal.body().get("fileName").asString()).as("sin rutas ni comillas").isEqualTo("Propuesta final.md");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        String imageId = upload(key, "logo.png", "image/png", png, owner).body().get("id").asString();
        assertThat(upload(key, "grande.bin", "application/octet-stream", new byte[1_100_000], owner).status()).as("más de 1 MB").isIn(413, 422);

        JsonNode listing = call("GET", "/api/issues/" + key + "/attachments", null, owner).body();
        assertThat(listing.get("available").asBoolean()).isTrue();
        assertThat(listing.get("items").findValuesAsString("fileName")).containsExactly("Propuesta final.md", "logo.png");

        Reply download = call("GET", "/api/attachments/" + proposal.body().get("id").asString() + "/content", null, owner);
        assertThat(download.text()).contains("Plan anual");
        assertThat(download.raw().headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment");
        Reply image = call("GET", "/api/attachments/" + imageId + "/content?inline=true", null, owner);
        assertThat(image.raw().body()).isEqualTo(png);
        assertThat(image.raw().headers().firstValue("Content-Type").orElse("")).isEqualTo("image/png");

        // Un agente lo ve en get_issue y lee el de texto.
        String runner = call("POST", "/api/auth/runner-tokens", Map.of("name", "Laptop"), owner).body().get("token").asString();
        JsonNode issue = tool(runner, "get_issue", Map.of("key", key));
        assertThat(issue.get("attachments").findValuesAsString("name")).contains("Propuesta final.md", "logo.png");
        assertThat(tool(runner, "read_attachment", Map.of("key", key, "id", proposal.body().get("id").asString())).get("content").asString())
                .contains("Plan anual");
        assertThat(toolError(runner, "read_attachment", Map.of("key", key, "id", imageId))).contains("no es de texto");

        assertThat(call("DELETE", "/api/attachments/" + imageId, null, owner).status()).isEqualTo(204);
        assertThat(call("GET", "/api/attachments/" + imageId + "/content", null, owner).status()).isEqualTo(404);
        assertThat(call("GET", "/api/issues/" + key + "/attachments", null, owner).body().get("items").size()).isEqualTo(1);
    }

    private JsonNode tool(String runner, String name, Map<String, Object> args) throws Exception {
        JsonNode r = call("POST", "/mcp", Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of("name", name, "arguments", args)), runner)
                .body().get("result");
        assertThat(r.path("isError").asBoolean(false)).as(r.toString()).isFalse();
        return json.readTree(r.get("content").get(0).get("text").asString());
    }

    private String toolError(String runner, String name, Map<String, Object> args) throws Exception {
        JsonNode r = call("POST", "/mcp", Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of("name", name, "arguments", args)), runner)
                .body().get("result");
        assertThat(r.path("isError").asBoolean(false)).isTrue();
        return r.get("content").get(0).get("text").asString();
    }
}
