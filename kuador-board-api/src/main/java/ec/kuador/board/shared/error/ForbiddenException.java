package ec.kuador.board.shared.error;

/** Quien pide la operación no puede hacerla (p. ej. un agente en una puerta humana). Se traduce a HTTP 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
