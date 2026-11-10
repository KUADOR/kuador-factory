package ec.kuador.board.auth.domain;

/** Puerto: algoritmo de hash de contraseñas (bcrypt en infraestructura). */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String hash);
}
