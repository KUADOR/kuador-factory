package ec.kuador.board.notify.infrastructure.channels;

import ec.kuador.board.notify.domain.Channel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Avisos por correo, con el mismo SMTP de los códigos del alta (spring.mail.*). Sin SMTP, el canal no está disponible. */
@Component
class EmailChannel implements Channel {

    private final ObjectProvider<JavaMailSender> sender;
    private final String from;

    EmailChannel(ObjectProvider<JavaMailSender> sender, @Value("${board.mail.from:Kuador Go <no-reply@kuador.com>}") String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public Kind kind() {
        return Kind.EMAIL;
    }

    @Override
    public boolean available() {
        return sender.getIfAvailable() != null;
    }

    @Override
    public void send(java.util.UUID workspaceId, String address, String subject, String body, String link) {
        JavaMailSender smtp = sender.getIfAvailable();
        if (smtp == null) throw new IllegalStateException("No hay SMTP configurado.");
        // Correo automático (RFC 3834): ningún buzón conectado al tablero lo vuelve tarjeta, ni responde fuera de oficina.
        jakarta.mail.internet.MimeMessage m = smtp.createMimeMessage();
        try {
            org.springframework.mail.javamail.MimeMessageHelper h = new org.springframework.mail.javamail.MimeMessageHelper(m, "UTF-8");
            h.setFrom(from);
            h.setTo(address);
            h.setSubject(subject);
            m.setHeader("Auto-Submitted", "auto-generated");
            m.setHeader("X-Kuador-Mail", "aviso");
            h.setText(body + (link == null ? "" : "\n\nÁbrelo en el tablero: " + link) + "\n\nCambia tus avisos en Mi cuenta → Notificaciones.\n");
        } catch (jakarta.mail.MessagingException e) {
            throw new IllegalStateException("No se pudo armar el correo: " + e.getMessage(), e);
        }
        smtp.send(m);
    }
}
