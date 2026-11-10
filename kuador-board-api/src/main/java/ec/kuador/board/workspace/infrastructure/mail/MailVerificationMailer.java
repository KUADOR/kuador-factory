package ec.kuador.board.workspace.infrastructure.mail;

import ec.kuador.board.workspace.domain.VerificationMailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Envía el código por SMTP (spring.mail.*). Sin SMTP configurado, solo en desarrollo y con
 * board.mail.log-codes=true, escribe el código en el log para poder probar el alta en local.
 * En cualquier otro caso el alta queda deshabilitada con un mensaje claro.
 */
@Component
public class MailVerificationMailer implements VerificationMailer {

    private static final Logger log = LoggerFactory.getLogger(MailVerificationMailer.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    private final boolean logCodes;

    public MailVerificationMailer(ObjectProvider<JavaMailSender> sender, @Value("${board.mail.from:Kuador Factory <no-reply@kuador.com>}") String from,
            @Value("${board.mail.log-codes:false}") boolean logCodes) {
        this.sender = sender;
        this.from = from;
        this.logCodes = logCodes;
    }

    @Override
    public void sendCode(String email, String code) {
        JavaMailSender smtp = sender.getIfAvailable();
        if (smtp == null) {
            if (!logCodes) {
                throw new MailUnavailableException();
            }
            log.warn("[SOLO DESARROLLO] Sin SMTP configurado. Código de verificación para {}: {}", email, code);
            return;
        }
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(email);
        m.setSubject("Tu código para Kuador Factory: " + code);
        m.setText("""
                Tu código de verificación es: %s

                Vence en 10 minutos. Si no pediste crear un espacio de trabajo en Kuador Factory, ignora este correo.
                """.formatted(code));
        try {
            smtp.send(m);
        } catch (MailException e) {
            log.error("No se pudo enviar el código a {}", email, e);
            throw new MailUnavailableException();
        }
    }

    /** El correo no está disponible: el alta no puede continuar. */
    public static class MailUnavailableException extends RuntimeException {
        MailUnavailableException() {
            super("No pudimos enviar el código en este momento. Intenta más tarde.");
        }
    }
}
