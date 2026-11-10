package ec.kuador.board.shared.error;

/** La operación choca con el estado actual (por ejemplo, una clave repetida). Se traduce a HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
