package ec.kuador.board.shared.mail;

/** Correo transaccional de la plataforma (códigos, enlaces, invitaciones). */
@FunctionalInterface
public interface Mailer {

    /** Envía un correo de texto; lanza {@link MailUnavailableException} si no se puede. */
    void send(String to, String subject, String text);

    /** El correo no está disponible: lo que dependía de él no puede continuar. */
    class MailUnavailableException extends RuntimeException {
        public MailUnavailableException() {
            super("No pudimos enviar el correo en este momento. Intenta más tarde.");
        }
    }
}
