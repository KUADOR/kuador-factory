package ec.kuador.board.shared.error;

/** Una regla de negocio no se cumple. Se traduce a HTTP 422. */
public class DomainException extends RuntimeException {

    public DomainException(String message) {
        super(message);
    }
}
