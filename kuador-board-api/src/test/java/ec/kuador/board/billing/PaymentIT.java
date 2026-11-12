package ec.kuador.board.billing;

import static org.assertj.core.api.Assertions.assertThat;

import ec.kuador.board.auth.application.AuthService;
import ec.kuador.board.workspace.application.SignupService;
import ec.kuador.board.workspace.domain.VerificationMailer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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

/** Pagar un plan con una pasarela configurada en la plataforma (PayPal falso), por retorno y por aviso, sin cobrar dos veces. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "board.bootstrap.admin-email=", "board.bootstrap.admin-password=", "board.agents.anthropic-api-key=",
        "board.sync.allowed-roots=", "board.platform.admins=ada@pagos.test", "board.web-url=https://tablero.test",
        "spring.config.import=optional:file:does-not-exist.env[.properties]"})
class PaymentIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static final Map<String, String> INBOX = new ConcurrentHashMap<>();
    static final List<String> ORDERS = new CopyOnWriteArrayList<>();
    static com.sun.net.httpserver.HttpServer paypal;

    static {
        try {
            paypal = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            paypal.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                String body = new String(ex.getRequestBody().readAllBytes());
                String out;
                if (path.equals("/v1/oauth2/token")) {
                    out = "{\"access_token\":\"tok\"}";
                } else if (path.equals("/v2/checkout/orders")) {
                    ORDERS.add(body);
                    String id = "ORDER-" + ORDERS.size();
                    out = "{\"id\":\"" + id + "\",\"status\":\"PAYER_ACTION_REQUIRED\",\"links\":[{\"rel\":\"payer-action\",\"href\":\"https://paypal.test/pay?token=" + id + "\"}]}";
                } else if (path.endsWith("/capture")) {
                    out = "{\"status\":\"COMPLETED\"}";
                } else if (path.equals("/v1/notifications/verify-webhook-signature")) {
                    out = "{\"verification_status\":\"" + (body.contains("\"transmission_sig\":\"buena\"") ? "SUCCESS" : "FAILURE") + "\"}";
                } else {
                    out = "{}";
                }
                byte[] bytes = out.getBytes();
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            });
            paypal.start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Clave de cifrado propia de la prueba (las credenciales de la pasarela se guardan cifradas). */
    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry r) {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        r.add("board.secrets-key", () -> java.util.Base64.getEncoder().encodeToString(key));
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

    record Reply(int status, JsonNode body) {}

    private Reply call(String method, String path, Object body, String token, Map<String, String> headers) throws Exception {
        var r = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : json.writeValueAsString(body)));
        if (token != null) r.header("Authorization", "Bearer " + token);
        headers.forEach(r::header);
        HttpResponse<String> res = http.send(r.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(res.statusCode(), res.body().isBlank() ? null : json.readTree(res.body()));
    }

    private Reply call(String method, String path, Object body, String token) throws Exception {
        return call(method, path, body, token, Map.of());
    }

    private String account(String email, String company, String plan) {
        signup.requestCode(email);
        signup.complete(signup.verifyCode(email, INBOX.get(email)), new SignupService.Company(company, null, null, null, null, null),
                "Persona", "clave-larga-de-prueba-1", plan);
        return auth.login(email, "clave-larga-de-prueba-1").token();
    }

    @Test
    void aWorkspacePaysItsPlanWithTheConfiguredGatewayOnce() throws Exception {
        String admin = account("ada@pagos.test", "Plataforma Pagos", null);
        String owner = account("leo@pagos.test", "Cliente Pagos", "TEAM");

        // La plataforma configura PayPal: el formulario sale de los campos del adaptador.
        JsonNode types = call("GET", "/api/platform/payment-types", null, admin).body();
        assertThat(types.findValuesAsString("type")).contains("PAYPAL");
        assertThat(call("GET", "/api/platform/payment-types", null, owner).status()).isEqualTo(403);
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("type", "PAYPAL");
        provider.put("name", "PayPal");
        provider.put("mode", "SANDBOX");
        provider.put("credentials", Map.of("clientId", "cid", "clientSecret", "s3cr3t", "webhookId", "WH-1",
                "apiUrl", "http://127.0.0.1:" + paypal.getAddress().getPort()));
        provider.put("active", true);
        provider.put("sortOrder", 10);
        JsonNode saved = call("PUT", "/api/platform/payment-providers/PAYPAL", provider, admin).body();
        assertThat(saved.toString()).doesNotContain("s3cr3t");
        assertThat(saved.get("secretsSet").toString()).contains("clientSecret");
        assertThat(saved.get("webhookUrl").asString()).endsWith("/api/payments/webhook/PAYPAL");
        // Guardar sin el secreto lo conserva.
        provider.put("credentials", Map.of("clientId", "cid", "webhookId", "WH-1", "apiUrl", "http://127.0.0.1:" + paypal.getAddress().getPort()));
        assertThat(call("PUT", "/api/platform/payment-providers/PAYPAL", provider, admin).status()).isEqualTo(200);

        // El espacio paga 12 meses de Equipo.
        assertThat(call("GET", "/api/workspace/plan/payment-options", null, owner).body().findValuesAsString("code")).containsExactly("PAYPAL");
        JsonNode checkout = call("POST", "/api/workspace/plan/checkout", Map.of("plan", "TEAM", "provider", "PAYPAL", "months", 12), owner).body();
        assertThat(checkout.get("payUrl").asString()).isEqualTo("https://paypal.test/pay?token=ORDER-1");
        assertThat(ORDERS.getFirst()).contains("\"value\":\"348.00\"", "\"currency_code\":\"USD\"", "https://tablero.test/workspace/plan/pago?id=");

        JsonNode paid = call("POST", "/api/workspace/plan/payments/" + checkout.get("paymentId").asString() + "/confirm", null, owner).body();
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        JsonNode plan = call("GET", "/api/workspace/plan", null, owner).body();
        assertThat(plan.get("status").asString()).isEqualTo("ACTIVE");
        Instant until = Instant.parse(plan.get("paidUntil").asString());
        assertThat(until).isAfter(Instant.now().plus(360, ChronoUnit.DAYS)).isBefore(Instant.now().plus(370, ChronoUnit.DAYS));

        // El aviso de PayPal del mismo cobro (y un segundo confirm) no extienden otra vez; uno falso se ignora.
        String event = "{\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\",\"resource\":{\"supplementary_data\":{\"related_ids\":{\"order_id\":\"ORDER-1\"}}}}";
        assertThat(call("POST", "/api/payments/webhook/PAYPAL", event, null, Map.of("PAYPAL-TRANSMISSION-SIG", "buena")).status()).isEqualTo(200);
        call("POST", "/api/workspace/plan/payments/" + checkout.get("paymentId").asString() + "/confirm", null, owner);
        assertThat(call("GET", "/api/workspace/plan", null, owner).body().get("paidUntil").asString()).isEqualTo(plan.get("paidUntil").asString());

        // Un segundo pago llega solo por aviso (la persona cerró la ventana): se activa igual y suma un mes.
        JsonNode second = call("POST", "/api/workspace/plan/checkout", Map.of("plan", "TEAM", "provider", "PAYPAL", "months", 1), owner).body();
        String fake = event.replace("ORDER-1", "ORDER-2");
        call("POST", "/api/payments/webhook/PAYPAL", fake, null, Map.of("PAYPAL-TRANSMISSION-SIG", "mala"));
        assertThat(call("GET", "/api/workspace/plan", null, owner).body().get("paidUntil").asString()).as("aviso no verificado").isEqualTo(plan.get("paidUntil").asString());
        call("POST", "/api/payments/webhook/PAYPAL", fake, null, Map.of("PAYPAL-TRANSMISSION-SIG", "buena"));
        Instant extended = Instant.parse(call("GET", "/api/workspace/plan", null, owner).body().get("paidUntil").asString());
        assertThat(extended).isAfter(until.plus(27, ChronoUnit.DAYS));
        assertThat(call("GET", "/api/workspace/plan/payments", null, owner).body().get(0).get("id").asString()).isEqualTo(second.get("paymentId").asString());

        assertThat(call("POST", "/api/workspace/plan/checkout", Map.of("plan", "FREE", "provider", "PAYPAL", "months", 1), owner).status()).isEqualTo(422);
    }
}
