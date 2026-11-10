package ec.kuador.board.auth.domain;

import ec.kuador.board.shared.error.DomainException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Correo y hash de contraseña de una persona. */
public record Credential(UUID memberId, String email, String passwordHash, Instant createdAt, Instant updatedAt) {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    public static final int MIN_PASSWORD = 10;
    public static final int MAX_PASSWORD = 128;

    public static String normalizeEmail(String email) {
        if (email == null || email.length() > 254 || !EMAIL.matcher(email.strip()).matches()) {
            throw new DomainException("El correo no es válido.");
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            throw new DomainException("La contraseña debe tener entre " + MIN_PASSWORD + " y " + MAX_PASSWORD + " caracteres.");
        }
    }
}
