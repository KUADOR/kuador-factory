package ec.kuador.board.workspace.domain;

import ec.kuador.board.shared.error.DomainException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Espacio de trabajo: una empresa que usa el tablero. Su marca (color base, logo y descripción)
 * pinta la interfaz de sus personas.
 */
/**
 * {@code brandOnColor}: color del texto encima del color de marca (botones principales); nulo =
 * automático, el que más contraste.
 */
public record Workspace(UUID id, String slug, String name, String description, String brandColor, String brandOnColor, String logo, Instant createdAt) {

    public static final String DEFAULT_BRAND = "#ffb81c";
    /** ~150 KB de imagen en base64. */
    public static final int MAX_LOGO = 200_000;

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,39}$");
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-f]{6}$");
    private static final Pattern LOGO = Pattern.compile("^data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/]+={0,2}$");

    public Workspace {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new DomainException("El identificador del espacio va en minúsculas, números o guiones (ej. kuador).");
        }
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new DomainException("El nombre del espacio es obligatorio (hasta 120 caracteres).");
        }
        name = name.strip();
        description = description == null || description.isBlank() ? null : description.strip();
        if (description != null && description.length() > 500) {
            throw new DomainException("La descripción de la empresa puede tener hasta 500 caracteres.");
        }
        brandColor = brandColor == null ? DEFAULT_BRAND : brandColor.strip().toLowerCase();
        if (!COLOR.matcher(brandColor).matches()) {
            throw new DomainException("El color base va en formato hexadecimal, ej. #ffb81c.");
        }
        brandOnColor = brandOnColor == null || brandOnColor.isBlank() ? null : brandOnColor.strip().toLowerCase();
        if (brandOnColor != null && !COLOR.matcher(brandOnColor).matches()) {
            throw new DomainException("El color del texto de los botones va en formato hexadecimal, ej. #ffffff.");
        }
        logo = logo == null || logo.isBlank() ? null : logo.strip();
        if (logo != null && (logo.length() > MAX_LOGO || !LOGO.matcher(logo).matches())) {
            throw new DomainException("El logo debe ser una imagen PNG, JPG o WebP de hasta 150 KB.");
        }
    }

    public static Workspace create(String slug, String name, String description, String brandColor, String brandOnColor, String logo, Instant now) {
        return new Workspace(UUID.randomUUID(), slug, name, description, brandColor, brandOnColor, logo, now);
    }

    public Workspace withBranding(String newName, String newDescription, String newBrandColor, String newBrandOnColor, String newLogo) {
        return new Workspace(id, slug, newName, newDescription, newBrandColor, newBrandOnColor, newLogo, createdAt);
    }

    /** Identificador a partir del nombre: «Clínica Andes» → «clinica-andes». */
    public static String slugOf(String name) {
        String s = java.text.Normalizer.normalize(name == null ? "" : name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 40) s = s.substring(0, 40).replaceAll("-+$", "");
        return s.length() < 2 ? "espacio" : s;
    }
}
