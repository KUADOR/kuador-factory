package ec.kuador.board.notify;

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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Avisos por correo y Telegram: vincular el chat, una pregunta de un agente, una puerta, preferencias y prueba. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=", "board.agents.anthropic-api-key=",
        "board.sync.allowed-roots=", "board.telegram.bot-token=123:abc", "board.telegram.bot-username=tablero_bot",
        "board.notifications.dispatch-every=200", "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class NotifyIT {

    /** Telegram falso: guarda lo que el bot envía y entrega los mensajes encolados por getUpdates. */
    static final List<Map<String, String>> SENT = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final java.util.Queue<String> INCOMING = new java.util.concurrent.ConcurrentLinkedQueue<>();
    static final List<org.springframework.mail.SimpleMailMessage> MAILS = new java.util.concurrent.CopyOnWriteArrayList<>();
    static com.sun.net.httpserver.HttpServer telegram;
    static int updateId = 1;

    static {
        try {
            telegram = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            telegram.createContext("/", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes());
                Map<String, String> form = new java.util.HashMap<>();
                for (String pair : body.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length == 2) form.put(kv[0], java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8));
                }
                String path = ex.getRequestURI().getPath();
                String result;
                if (path.endsWith("/sendMessage")) {
                    SENT.add(form);
                    result = "{}";
                } else if (path.endsWith("/getUpdates")) {
                    StringBuilder list = new StringBuilder("[");
                    String text;
                    while ((text = INCOMING.poll()) != null) {
                        if (list.length() > 1) list.append(',');
                        list.append("{\"update_id\":").append(updateId++).append(",\"message\":{\"chat\":{\"id\":777,\"type\":\"private\"},\"text\":\"")
                                .append(text).append("\"}}");
                    }
                    result = list.append(']').toString();
                } else {
                    result = "true";
                }
                byte[] out = ("{\"ok\":true,\"result\":" + result + "}").getBytes();
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            telegram.start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("board.telegram.api-url", () -> "http://127.0.0.1:" + telegram.getAddress().getPort());
    }

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

        @Bean
        @Primary
        org.springframework.mail.javamail.JavaMailSender smtp() {
            return new org.springframework.mail.javamail.JavaMailSenderImpl() {
                @Override
                public void send(org.springframework.mail.SimpleMailMessage... messages) {
                    MAILS.addAll(List.of(messages));
                }
            };
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

    private void eventually(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    @Test
    void aPersonGetsEmailAndTelegramWhenSomethingNeedsThem() throws Exception {
        signup.requestCode("noa@nt.test");
        signup.complete(signup.verifyCode("noa@nt.test", INBOX.get("noa@nt.test")),
                new SignupService.Company("Nt", null, null, null, null, null), "Noa", "clave-de-noa-123", "FACTORY");
        String owner = auth.login("noa@nt.test", "clave-de-noa-123").token();
        JsonNode view = call("GET", "/api/notifications", null, owner).body();
        assertThat(view.get("emailAvailable").asBoolean()).isTrue();
        assertThat(view.get("telegramAvailable").asBoolean()).isTrue();
        assertThat(view.get("email").asString()).isEqualTo("noa@nt.test");

        // Vincular Telegram: el enlace abre el bot con un código; «/start código» guarda el chat.
        String url = call("POST", "/api/notifications/telegram/link", null, owner).body().get("url").asString();
        assertThat(url).startsWith("https://t.me/tablero_bot?start=");
        INCOMING.add("/start " + url.substring(url.indexOf("start=") + 6));
        eventually("el chat quedó vinculado", () -> {
            try {
                return !call("GET", "/api/notifications", null, owner).body().get("settings").get("telegramChatId").isNull();
            } catch (Exception e) {
                return false;
            }
        });
        assertThat(SENT).anySatisfy(m -> assertThat(m.get("text")).contains("Listo, Noa"));
        INCOMING.add("/start codigo-viejo");
        eventually("un código inválido se explica", () -> SENT.stream().anyMatch(m -> m.get("text").contains("ya se usó o venció")));

        // Un agente pide una decisión: le llega a la persona por los dos canales, con la pregunta.
        String runner = call("POST", "/api/auth/runner-tokens", Map.of("name", "Laptop"), owner).body().get("token").asString();
        call("POST", "/api/projects", Map.of("key", "NT", "name", "Nt", "templateKey", "SOFTWARE_FACTORY"), owner);
        String key = call("POST", "/api/projects/NT/issues", Map.of("title", "Agenda", "type", "STORY", "status", "REFINEMENT"), owner).body().get("key").asString();
        tool(runner, "take_issue", Map.of("key", key, "agent", "PRODUCT_OWNER"), false);
        tool(runner, "ask_human", Map.of("key", key, "agent", "PRODUCT_OWNER", "question", "¿Citas de 30 o 60 minutos?"), false);
        eventually("correo con la pregunta", () -> MAILS.stream().anyMatch(m -> m.getSubject().contains(key + " · Agente PO / Analista te necesita")
                && m.getText().contains("¿Citas de 30 o 60 minutos?") && m.getText().contains("/projects/NT?issue=" + key)));
        eventually("Telegram con la pregunta", () -> SENT.stream().anyMatch(m -> "777".equals(m.get("chat_id")) && m.get("text").contains("¿Citas de 30 o 60 minutos?")));

        // Llega a una puerta: avisa a quien aprueba (la dueña). Con ese aviso apagado, no llega.
        // La dueña responde y se lo devuelve al agente, que entrega a la puerta de Aprobación.
        String agentId = call("GET", "/api/members", null, owner).body().valueStream().filter(m -> "PRODUCT_OWNER".equals(m.get("role").asString()))
                .findFirst().orElseThrow().get("id").asString();
        call("PUT", "/api/issues/" + key + "/assignee", Map.of("assigneeId", agentId), owner);
        tool(runner, "deliver", Map.of("key", key, "agent", "PRODUCT_OWNER", "comment", "Lista."), false);
        eventually("aviso de la puerta", () -> MAILS.stream().anyMatch(m -> m.getSubject().equals(key + " · espera tu aprobación en «Aprobación»")));

        assertThat(call("PUT", "/api/notifications", Map.of("email", true, "telegram", true, "onAssigned", true, "onGates", false, "onPlans", true), owner)
                .body().get("settings").get("onGates").asBoolean()).isFalse();
        call("POST", "/api/issues/" + key + "/review", Map.of("approve", false, "comment", "Falta el horario."), owner);
        int before = MAILS.size();
        String other = call("POST", "/api/projects/NT/issues", Map.of("title", "Otra", "type", "STORY", "status", "REFINEMENT"), owner).body().get("key").asString();
        tool(runner, "take_issue", Map.of("key", other, "agent", "PRODUCT_OWNER"), false);
        tool(runner, "deliver", Map.of("key", other, "agent", "PRODUCT_OWNER"), false);
        Thread.sleep(1500);
        assertThat(MAILS.subList(before, MAILS.size())).as("sin avisos de puertas").noneMatch(m -> m.getSubject().contains("espera tu aprobación"));

        // Prueba de canales y desvincular.
        assertThat(call("POST", "/api/notifications/test", null, owner).body().get("channels").toString()).contains("EMAIL", "TELEGRAM");
        eventually("llega la prueba", () -> MAILS.stream().anyMatch(m -> m.getSubject().equals("Prueba de avisos del tablero")));
        assertThat(call("DELETE", "/api/notifications/telegram", null, owner).body().get("settings").get("telegramChatId").isNull()).isTrue();
        assertThat(call("GET", "/api/notifications", null, runner).status()).as("la conexión de agentes no abre esto").isEqualTo(401);
    }
}
