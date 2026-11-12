package ec.kuador.board.billing.infrastructure.gateways;

import ec.kuador.board.billing.domain.Payment;
import ec.kuador.board.billing.domain.PaymentGateway;
import ec.kuador.board.billing.domain.PaymentProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PayPal con la API de órdenes (v2): se crea una orden, la persona la aprueba en PayPal y el tablero la cobra.
 * Los avisos (webhooks) se verifican con PayPal antes de creerlos.
 */
@Component
class PayPalGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(PayPalGateway.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String type() {
        return "PAYPAL";
    }

    @Override
    public String label() {
        return "PayPal";
    }

    @Override
    public List<Field> fields() {
        return List.of(
                new Field("clientId", "Client ID", false, true, "De tu app en developer.paypal.com → Apps & Credentials."),
                new Field("clientSecret", "Secret", true, true, "El secreto de la misma app."),
                new Field("webhookId", "Webhook ID", false, false, "Opcional: el id del webhook que creaste en PayPal apuntando a la URL que muestra esta página."),
                new Field("apiUrl", "URL de la API", false, false, "Opcional: solo para pruebas; por defecto la de PayPal según el modo."));
    }

    @Override
    public Checkout start(PaymentProvider p, Payment payment, String description, String returnUrl, String cancelUrl) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("return_url", returnUrl);
        context.put("cancel_url", cancelUrl);
        context.put("user_action", "PAY_NOW");
        context.put("brand_name", "Kuador Go");
        context.put("shipping_preference", "NO_SHIPPING");
        Map<String, Object> order = Map.of(
                "intent", "CAPTURE",
                "purchase_units", List.of(Map.of(
                        "reference_id", payment.id().toString(),
                        "custom_id", payment.id().toString(),
                        "description", description,
                        "amount", Map.of("currency_code", payment.currency(), "value", payment.amount().setScale(2).toPlainString()))),
                "payment_source", Map.of("paypal", Map.of("experience_context", context)));
        JsonNode created = post(p, "/v2/checkout/orders", json.writeValueAsString(order));
        String approve = null;
        for (JsonNode link : created.path("links")) {
            String rel = link.path("rel").asString("");
            if (rel.equals("payer-action") || rel.equals("approve")) approve = link.path("href").asString(null);
        }
        if (approve == null) throw new IllegalStateException("PayPal no devolvió el enlace para pagar.");
        return new Checkout(created.path("id").asString(), approve);
    }

    @Override
    public boolean capture(PaymentProvider p, String orderId) {
        try {
            return "COMPLETED".equals(post(p, "/v2/checkout/orders/" + orderId + "/capture", "{}").path("status").asString(""));
        } catch (PayPalException e) {
            // Ya cobrada (p. ej. la cobró antes el aviso de PayPal): se consulta su estado.
            if (e.status == 422) return "COMPLETED".equals(get(p, "/v2/checkout/orders/" + orderId).path("status").asString(""));
            throw e;
        }
    }

    @Override
    public Optional<Notice> verify(PaymentProvider p, Map<String, String> headers, String body) {
        String webhookId = p.credential("webhookId");
        if (webhookId.isEmpty()) return Optional.empty();
        try {
            JsonNode event = json.readTree(body);
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("auth_algo", headers.getOrDefault("paypal-auth-algo", ""));
            check.put("cert_url", headers.getOrDefault("paypal-cert-url", ""));
            check.put("transmission_id", headers.getOrDefault("paypal-transmission-id", ""));
            check.put("transmission_sig", headers.getOrDefault("paypal-transmission-sig", ""));
            check.put("transmission_time", headers.getOrDefault("paypal-transmission-time", ""));
            check.put("webhook_id", webhookId);
            check.put("webhook_event", event);
            if (!"SUCCESS".equals(post(p, "/v1/notifications/verify-webhook-signature", json.writeValueAsString(check)).path("verification_status").asString(""))) {
                return Optional.empty();
            }
            return switch (event.path("event_type").asString("")) {
                case "CHECKOUT.ORDER.APPROVED" -> Optional.of(new Notice(event.path("resource").path("id").asString(), false));
                case "PAYMENT.CAPTURE.COMPLETED" ->
                        Optional.of(new Notice(event.path("resource").path("supplementary_data").path("related_ids").path("order_id").asString(), true));
                default -> Optional.empty();
            };
        } catch (RuntimeException e) {
            log.warn("Aviso de PayPal no válido: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ---- HTTP ----

    private String base(PaymentProvider p) {
        String override = p.credential("apiUrl");
        if (!override.isEmpty()) return override.replaceAll("/+$", "");
        return p.mode() == PaymentProvider.Mode.LIVE ? "https://api-m.paypal.com" : "https://api-m.sandbox.paypal.com";
    }

    private String accessToken(PaymentProvider p) {
        String basic = Base64.getEncoder().encodeToString((p.credential("clientId") + ":" + p.credential("clientSecret")).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(base(p) + "/v1/oauth2/token")).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + basic).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build();
        return send(req).path("access_token").asString();
    }

    private JsonNode post(PaymentProvider p, String path, String body) {
        return send(HttpRequest.newBuilder(URI.create(base(p) + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken(p)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private JsonNode get(PaymentProvider p, String path) {
        return send(HttpRequest.newBuilder(URI.create(base(p) + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken(p)).GET().build());
    }

    private JsonNode send(HttpRequest req) {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                String detail = res.body() == null ? "" : res.body();
                throw new PayPalException(res.statusCode(), "PayPal respondió " + res.statusCode() + (detail.length() > 300 ? "" : ": " + detail));
            }
            return res.body() == null || res.body().isBlank() ? json.createObjectNode() : json.readTree(res.body());
        } catch (java.io.IOException e) {
            throw new PayPalException(0, "No se pudo hablar con PayPal: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PayPalException(0, "Interrumpido");
        }
    }

    static final class PayPalException extends RuntimeException {
        final int status;

        PayPalException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
