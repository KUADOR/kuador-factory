package ec.kuador.board.billing.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Puerto: un adaptador de pasarela de pago. Para sumar un proveedor se implementa esta interfaz; la pasarela se
 * da de alta y se configura en la base, sin tocar el resto del código.
 */
public interface PaymentGateway {

    /** Tipo que atiende (p. ej. PAYPAL). */
    String type();

    /** Nombre para quien configura. */
    String label();

    /** Credenciales que pide; las secretas no se vuelven a mostrar. */
    List<Field> fields();

    record Field(String key, String label, boolean secret, boolean required, String hint) {}

    /** Inicia el cobro y devuelve el id externo y la URL donde la persona paga. */
    Checkout start(PaymentProvider provider, Payment payment, String description, String returnUrl, String cancelUrl);

    record Checkout(String externalId, String payUrl) {}

    /** Cobra lo que la persona aprobó. {@code true} si quedó pagado. */
    boolean capture(PaymentProvider provider, String externalId);

    /**
     * Verifica un aviso de la pasarela. Devuelve el id externo del cobro y si ya está cobrado ({@code captured}) o
     * solo aprobado; vacío si el aviso no es auténtico o no interesa.
     */
    Optional<Notice> verify(PaymentProvider provider, Map<String, String> headers, String body);

    record Notice(String externalId, boolean captured) {}
}
