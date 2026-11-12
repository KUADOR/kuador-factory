package ec.kuador.board.billing.domain;

import ec.kuador.board.shared.error.DomainException;
import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Un plan que se vende. {@code maxMembers}/{@code maxProjects} nulos = sin límite. {@code factory}: incluye la
 * fábrica de software. {@code trialDays}: días de prueba al elegirlo por primera vez (0 = se paga desde el inicio).
 */
public record Plan(
        String code,
        String name,
        String tagline,
        BigDecimal priceMonthly,
        String currency,
        List<String> features,
        Integer maxMembers,
        Integer maxProjects,
        boolean factory,
        int trialDays,
        boolean highlighted,
        int sortOrder,
        boolean active) {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    public Plan {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new DomainException("El código del plan va en mayúsculas, números o guion bajo (ej. TEAM).");
        }
        name = required(name, "El nombre del plan", 60);
        tagline = required(tagline, "La frase del plan", 200);
        if (priceMonthly == null || priceMonthly.signum() < 0 || priceMonthly.stripTrailingZeros().scale() > 2) {
            throw new DomainException("El precio mensual es un valor de 0 o más, con hasta 2 decimales.");
        }
        currency = currency == null || currency.isBlank() ? "USD" : currency.strip().toUpperCase();
        if (!CURRENCY.matcher(currency).matches()) throw new DomainException("La moneda va en código de 3 letras, ej. USD.");
        features = features == null ? List.of() : features.stream().map(String::strip).filter(f -> !f.isEmpty()).toList();
        if (features.size() > 12 || features.stream().anyMatch(f -> f.length() > 120)) {
            throw new DomainException("Un plan tiene hasta 12 características de hasta 120 caracteres.");
        }
        if (maxMembers != null && maxMembers < 1 || maxProjects != null && maxProjects < 1) {
            throw new DomainException("Los límites son 1 o más; déjalos vacíos para no limitar.");
        }
        if (trialDays < 0 || trialDays > 365) throw new DomainException("La prueba dura entre 0 y 365 días.");
    }

    public boolean free() {
        return priceMonthly.signum() == 0;
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.strip().length() > max) {
            throw new DomainException(label + " es obligatorio (hasta " + max + " caracteres).");
        }
        return value.strip();
    }
}
