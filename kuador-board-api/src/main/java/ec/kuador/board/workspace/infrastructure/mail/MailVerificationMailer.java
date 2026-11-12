package ec.kuador.board.workspace.infrastructure.mail;

import ec.kuador.board.shared.mail.Mailer;
import ec.kuador.board.workspace.domain.VerificationMailer;
import org.springframework.stereotype.Component;

/** Envía el código del alta por el correo de la plataforma. */
@Component
class MailVerificationMailer implements VerificationMailer {

    private final Mailer mailer;

    MailVerificationMailer(Mailer mailer) {
        this.mailer = mailer;
    }

    @Override
    public void sendCode(String email, String code) {
        mailer.send(email, "Tu código para Kuador Go: " + code, """
                Tu código de verificación es: %s

                Vence en 10 minutos. Si no pediste crear un espacio de trabajo en Kuador Go, ignora este correo.
                """.formatted(code));
    }
}
