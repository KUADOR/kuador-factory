package ec.kuador.board.notify.infrastructure.channels;

import ec.kuador.board.notify.domain.Channel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Avisos por correo, con el mismo SMTP de los códigos del alta (spring.mail.*). Sin SMTP, el canal no está disponible. */
@Component
class EmailChannel implements Channel {

    private final ObjectProvider<JavaMailSender> sender;
    private final String from;

    EmailChannel(ObjectProvider<JavaMailSender> sender, @Value("${board.mail.from:Kuador Factory <no-reply@kuador.com>}") String from) {
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
    public void send(String address, String subject, String body, String link) {
        JavaMailSender smtp = sender.getIfAvailable();
        if (smtp == null) throw new IllegalStateException("No hay SMTP configurado.");
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(address);
        m.setSubject(subject);
        m.setText(body + (link == null ? "" : "\n\nÁbrelo en el tablero: " + link) + "\n\nCambia tus avisos en Mi cuenta → Notificaciones.\n");
        smtp.send(m);
    }
}
