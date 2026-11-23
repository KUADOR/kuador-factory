package ec.kuador.board.shared.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Correo transaccional (códigos, enlaces, invitaciones) por SMTP ({@code spring.mail.*}). Sin SMTP, solo en
 * desarrollo y con {@code board.mail.log-codes=true}, escribe el correo en el log para probar en local; en
 * cualquier otro caso falla con {@link MailUnavailableException}.
 */
@Component
public class SmtpMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailer.class);

    private final ObjectProvider<JavaMailSender> sender;
    private final String from;
    private final boolean logOnly;

    SmtpMailer(ObjectProvider<JavaMailSender> sender, @Value("${board.mail.from:Kuador Go <no-reply@kuador.com>}") String from,
            @Value("${board.mail.log-codes:false}") boolean logOnly) {
        this.sender = sender;
        this.from = from;
        this.logOnly = logOnly;
    }

    @Override
    public void send(String to, String subject, String text) {
        JavaMailSender smtp = sender.getIfAvailable();
        if (smtp == null) {
            if (!logOnly) throw new Mailer.MailUnavailableException();
            log.warn("[SOLO DESARROLLO] Sin SMTP configurado. Correo para {} — {}\n{}", to, subject, text);
            return;
        }
        // Correo automático (RFC 3834): ningún buzón conectado al tablero lo vuelve tarjeta, ni responde fuera de oficina.
        jakarta.mail.internet.MimeMessage m = smtp.createMimeMessage();
        try {
            org.springframework.mail.javamail.MimeMessageHelper h = new org.springframework.mail.javamail.MimeMessageHelper(m, "UTF-8");
            h.setFrom(from);
            h.setTo(to);
            h.setSubject(subject);
            m.setHeader("Auto-Submitted", "auto-generated");
            m.setHeader("X-Kuador-Mail", "aviso");
            h.setText(text);
        } catch (jakarta.mail.MessagingException e) {
            throw new Mailer.MailUnavailableException();
        }
        try {
            smtp.send(m);
        } catch (MailException e) {
            log.error("No se pudo enviar el correo a {}", to, e);
            throw new Mailer.MailUnavailableException();
        }
    }
}
