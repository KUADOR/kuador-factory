package ec.kuador.board.billing.infrastructure.web;

import ec.kuador.board.billing.application.PaymentService;
import ec.kuador.board.billing.domain.Payment;
import ec.kuador.board.billing.domain.PaymentProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Pagos: pasarelas (plataforma), pagar un plan (espacio) y los avisos de las pasarelas. */
@RestController
class PaymentController {

    private final PaymentService payments;

    PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    // ---- Plataforma ----

    record ProviderRequest(@NotBlank @Size(max = 30) String type, @NotBlank @Size(max = 60) String name, PaymentProvider.Mode mode,
            Map<String, @Size(max = 500) String> credentials, boolean active, int sortOrder) {}

    @GetMapping("/api/platform/payment-types")
    List<PaymentService.GatewayType> types() {
        return payments.types();
    }

    @GetMapping("/api/platform/payment-providers")
    List<PaymentService.ProviderView> providers() {
        return payments.providers();
    }

    @PutMapping("/api/platform/payment-providers/{code}")
    PaymentService.ProviderView save(@PathVariable String code, @Valid @RequestBody ProviderRequest r) {
        return payments.saveProvider(code, r.type(), r.name(), r.mode(), r.credentials(), r.active(), r.sortOrder());
    }

    // ---- Espacio ----

    record CheckoutRequest(@NotBlank @Size(max = 30) String plan, @NotBlank @Size(max = 30) String provider, int months) {}

    @GetMapping("/api/workspace/plan/payment-options")
    List<PaymentService.Option> options() {
        return payments.options();
    }

    @GetMapping("/api/workspace/plan/payments")
    List<Payment> recent() {
        return payments.recent();
    }

    @PostMapping("/api/workspace/plan/checkout")
    PaymentService.CheckoutResult checkout(@Valid @RequestBody CheckoutRequest r) {
        return payments.checkout(r.plan(), r.provider(), r.months());
    }

    @PostMapping("/api/workspace/plan/payments/{id}/confirm")
    Payment confirm(@PathVariable UUID id) {
        return payments.confirm(id);
    }

    // ---- Avisos de la pasarela (sin sesión; el adaptador verifica que sean auténticos) ----

    @PostMapping("/api/payments/webhook/{code}")
    ResponseEntity<Void> webhook(@PathVariable String code, @RequestBody(required = false) String body, HttpServletRequest request) {
        Map<String, String> headers = Collections.list(request.getHeaderNames()).stream()
                .collect(Collectors.toMap(h -> h.toLowerCase(Locale.ROOT), request::getHeader, (a, b) -> a));
        payments.onNotice(code.toUpperCase(Locale.ROOT), headers, body == null ? "" : body);
        return ResponseEntity.ok().build();
    }
}
