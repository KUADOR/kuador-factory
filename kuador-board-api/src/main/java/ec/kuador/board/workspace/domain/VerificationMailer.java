package ec.kuador.board.workspace.domain;

/** Puerto: envía el código de verificación al correo. */
public interface VerificationMailer {

    void sendCode(String email, String code);
}
