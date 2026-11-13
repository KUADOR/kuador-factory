package ec.kuador.board.billing.application;

import ec.kuador.board.billing.domain.Payment;
import ec.kuador.board.billing.domain.PaymentGateway;
import ec.kuador.board.billing.domain.PaymentProvider;
import ec.kuador.board.billing.domain.PaymentStore;
import ec.kuador.board.billing.domain.Plan;
import ec.kuador.board.shared.crypto.SecretBox;
import ec.kuador.board.shared.error.DomainException;
import ec.kuador.board.shared.error.NotFoundException;
import ec.kuador.board.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Cobros de planes con pasarelas configurables. Cada pasarela es una fila (código, tipo, credenciales cifradas,
 * modo, activa) y un adaptador por tipo ({@link PaymentGateway}); cambiar o sumar proveedores no toca el resto.
 * Se paga por meses: al confirmarse, la licencia queda activa hasta la nueva fecha.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");
    private static final List<Integer> PERIODS = List.of(1, 12);

    private final PaymentStore store;
    private final Map<String, PaymentGateway> gateways;
    private final BillingService billing;
    private final SecretBox secrets;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String webUrl;
    private final String publicUrl;
    private final ObjectMapper json = new ObjectMapper();
    private final org.springframework.context.ApplicationEventPublisher events;

    PaymentService(PaymentStore store, List<PaymentGateway> gateways, BillingService billing, SecretBox secrets, PlatformTransactionManager txManager,
            Clock clock, org.springframework.context.ApplicationEventPublisher events, @Value("${board.web-url:http://localhost:3600}") String webUrl,
            @Value("${board.public-url:http://localhost:8091}") String publicUrl) {
        this.events = events;
        this.store = store;
        this.gateways = gateways.stream().collect(Collectors.toMap(PaymentGateway::type, Function.identity()));
        this.billing = billing;
        this.secrets = secrets;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.webUrl = webUrl.replaceAll("/+$", "");
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    // ---- Plataforma: tipos disponibles y pasarelas configuradas ----

    public record GatewayType(String type, String label, List<PaymentGateway.Field> fields) {}

    /** Pasarela vista por quien administra: qué credenciales están puestas, nunca los secretos. */
    public record ProviderView(String code, String type, String name, PaymentProvider.Mode mode, boolean active, int sortOrder,
            Map<String, String> values, List<String> secretsSet, String webhookUrl) {}

    /** Pasarela visible para pagar. */
    public record Option(String code, String name) {}

    public List<GatewayType> types() {
        return gateways.values().stream().map(g -> new GatewayType(g.type(), g.label(), g.fields())).toList();
    }

    public List<ProviderView> providers() {
        return store.providers().stream().map(this::view).toList();
    }

    /** Crea o actualiza; una credencial secreta vacía conserva la guardada. */
    public ProviderView saveProvider(String code, String type, String name, PaymentProvider.Mode mode, Map<String, String> credentials, boolean active,
            int sortOrder) {
        if (code == null || !CODE.matcher(code).matches()) throw new DomainException("El código va en mayúsculas, números o guion bajo (ej. PAYPAL).");
        PaymentGateway gateway = gateway(type);
        if (name == null || name.isBlank() || name.strip().length() > 60) throw new DomainException("El nombre es obligatorio (hasta 60 caracteres).");
        Map<String, String> before = store.provider(code).map(p -> decrypt(p.credentialsEncrypted())).orElse(Map.of());
        Map<String, String> merged = new LinkedHashMap<>();
        for (PaymentGateway.Field f : gateway.fields()) {
            String value = credentials == null ? null : credentials.get(f.key());
            if ((value == null || value.isBlank()) && f.secret()) value = before.get(f.key());
            value = value == null ? "" : value.strip();
            if (f.required() && value.isEmpty()) throw new DomainException("Falta «" + f.label() + "».");
            if (!value.isEmpty()) merged.put(f.key(), value);
        }
        PaymentStore.StoredProvider saved = new PaymentStore.StoredProvider(code, gateway.type(), name.strip(), mode == null ? PaymentProvider.Mode.SANDBOX : mode,
                secrets.encrypt(json.writeValueAsString(merged)), active, sortOrder);
        tx.executeWithoutResult(s -> store.saveProvider(saved, clock.instant()));
        return view(saved);
    }

    /** Pasarelas activas, para elegir con cuál pagar. */
    public List<Option> options() {
        return store.providers().stream().filter(PaymentStore.StoredProvider::active).filter(p -> gateways.containsKey(p.type()))
                .map(p -> new Option(p.code(), p.name())).toList();
    }

    // ---- Espacio: pagar un plan ----

    public record CheckoutResult(UUID paymentId, String payUrl) {}

    /** Inicia el pago de {@code months} meses de {@code planCode} con {@code providerCode}; devuelve dónde pagar. */
    public CheckoutResult checkout(String planCode, String providerCode, int months, UUID requestedBy) {
        UUID ws = TenantContext.require();
        if (!PERIODS.contains(months)) throw new DomainException("Se paga por 1 o 12 meses.");
        Plan plan = billing.plan(planCode == null ? "" : planCode.toUpperCase()).filter(Plan::active)
                .orElseThrow(() -> new DomainException("Ese plan no está disponible."));
        if (plan.free()) throw new DomainException("El plan " + plan.name() + " es gratis: no hay nada que pagar.");
        PaymentProvider provider = activeProvider(providerCode);
        Payment payment = new Payment(UUID.randomUUID(), ws, plan.code(), provider.code(), plan.priceMonthly().multiply(BigDecimal.valueOf(months)),
                plan.currency(), months, Payment.Status.CREATED, null, clock.instant(), null, requestedBy);
        tx.executeWithoutResult(s -> store.save(payment));
        String description = "Kuador Go · Plan " + plan.name() + " · " + months + (months == 1 ? " mes" : " meses");
        PaymentGateway.Checkout checkout;
        try {
            checkout = gateway(provider.type()).start(provider, payment, description, webUrl + "/workspace/plan/pago?id=" + payment.id(),
                    webUrl + "/workspace/plan?pago=cancelado");
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> store.markFailed(payment.id()));
            log.error("No se pudo iniciar el pago con {}: {}", provider.code(), e.getMessage());
            throw new DomainException("No pudimos iniciar el pago con " + provider.name() + ". Intenta de nuevo en unos minutos.");
        }
        tx.executeWithoutResult(s -> store.setExternalId(payment.id(), checkout.externalId()));
        return new CheckoutResult(payment.id(), checkout.payUrl());
    }

    /** Al volver de la pasarela: cobra lo aprobado y activa el plan. Devuelve el pago actualizado. */
    public Payment confirm(UUID paymentId) {
        UUID ws = TenantContext.require();
        Payment payment = store.find(paymentId).filter(p -> p.workspaceId().equals(ws)).orElseThrow(() -> new NotFoundException("No existe ese pago."));
        if (payment.status() == Payment.Status.PAID || payment.externalId() == null) return payment;
        PaymentProvider provider = provider(payment.providerCode());
        boolean paid;
        try {
            paid = gateway(provider.type()).capture(provider, payment.externalId());
        } catch (RuntimeException e) {
            log.error("No se pudo confirmar el pago {}: {}", paymentId, e.getMessage());
            throw new DomainException("No pudimos confirmar el pago con " + provider.name() + ". Si ya pagaste, se activará en unos minutos.");
        }
        if (paid) settle(payment);
        return store.find(paymentId).orElseThrow();
    }

    /** Aviso de la pasarela (webhook). Solo se cree si el adaptador lo verifica. */
    public void onNotice(String providerCode, Map<String, String> headers, String body) {
        Optional<PaymentProvider> provider = Optional.ofNullable(providerCode).flatMap(c -> store.provider(c)).map(this::decrypted);
        if (provider.isEmpty()) return;
        PaymentGateway gateway = gateways.get(provider.get().type());
        if (gateway == null) return;
        gateway.verify(provider.get(), headers, body).ifPresent(notice -> store.findByExternal(provider.get().code(), notice.externalId()).ifPresent(payment -> {
            if (payment.status() == Payment.Status.PAID) return;
            boolean paid = notice.captured() || gateway.capture(provider.get(), notice.externalId());
            if (paid) settle(payment);
        }));
    }

    public List<Payment> recent() {
        return store.recent(TenantContext.require(), 10);
    }

    // ---- Apoyo ----

    /** Marca pagado (una sola vez), extiende la licencia y publica el recibo. */
    private void settle(Payment payment) {
        Instant now = clock.instant();
        ec.kuador.board.billing.domain.Subscription extended = tx.execute(s -> store.markPaid(payment.id(), now)
                ? billing.extendPaid(payment.workspaceId(), payment.planCode(), payment.months())
                : null);
        if (extended == null) return;
        String planName = billing.plan(payment.planCode()).map(Plan::name).orElse(payment.planCode());
        String providerName = store.provider(payment.providerCode()).map(PaymentStore.StoredProvider::name).orElse(payment.providerCode());
        events.publishEvent(new ec.kuador.board.billing.PaymentReceived(payment.workspaceId(), payment.id(), payment.requestedBy(), planName,
                payment.months(), payment.amount(), payment.currency(), now, extended.paidUntil(), providerName, payment.externalId()));
    }

    private PaymentProvider activeProvider(String code) {
        PaymentProvider p = provider(code == null ? "" : code.toUpperCase());
        if (!p.active()) throw new DomainException("Esa forma de pago no está disponible.");
        return p;
    }

    private PaymentProvider provider(String code) {
        return store.provider(code).map(this::decrypted).orElseThrow(() -> new DomainException("Esa forma de pago no está disponible."));
    }

    private PaymentProvider decrypted(PaymentStore.StoredProvider p) {
        return new PaymentProvider(p.code(), p.type(), p.name(), p.mode(), decrypt(p.credentialsEncrypted()), p.active(), p.sortOrder());
    }

    private Map<String, String> decrypt(String stored) {
        return json.readValue(secrets.decrypt(stored), new TypeReference<Map<String, String>>() {});
    }

    private PaymentGateway gateway(String type) {
        PaymentGateway g = type == null ? null : gateways.get(type.toUpperCase());
        if (g == null) throw new DomainException("No hay un adaptador para la pasarela " + type + ".");
        return g;
    }

    private ProviderView view(PaymentStore.StoredProvider p) {
        Map<String, String> creds = decrypt(p.credentialsEncrypted());
        PaymentGateway g = gateways.get(p.type());
        Map<String, String> values = new LinkedHashMap<>();
        List<String> secretsSet = new java.util.ArrayList<>();
        if (g != null) {
            for (PaymentGateway.Field f : g.fields()) {
                if (!creds.containsKey(f.key())) continue;
                if (f.secret()) secretsSet.add(f.key());
                else values.put(f.key(), creds.get(f.key()));
            }
        }
        return new ProviderView(p.code(), p.type(), p.name(), p.mode(), p.active(), p.sortOrder(), values, secretsSet,
                publicUrl + "/api/payments/webhook/" + p.code());
    }
}
