package ec.kuador.board.shared.error;

/** Un recurso pedido no existe. Se traduce a HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
