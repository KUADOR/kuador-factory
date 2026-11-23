package ec.kuador.board.connections.domain;

import ec.kuador.board.shared.error.DomainException;
import java.util.Locale;

/**
 * Un buzón que se conecta con su correo y contraseña por IMAP (leer) y SMTP (enviar): Hostinger, cPanel, Zoho… Se
 * guarda entero, cifrado, como credencial de la conexión. {@code smtpSsl}: SSL desde el principio (puerto 465); si no,
 * STARTTLS obligatorio (587). Nulo = según el puerto.
 */
public record MailAccount(String email, String password, String imapHost, int imapPort, String smtpHost, int smtpPort, Boolean smtpSsl) {

    /** Así empieza la dirección de una conexión de correo por IMAP. */
    public static final String SCHEME = "imaps://";

    public MailAccount {
        if (email == null || !email.strip().matches("[^\\s<>@,;\"]+@[^\\s<>@,;\".]+(\\.[^\\s<>@,;\".]+)+")) {
            throw new DomainException("Indica el correo completo (ej. facturas@empresa.com).");
        }
        if (password == null || password.isEmpty()) throw new DomainException("Falta la contraseña del correo.");
        if (imapPort < 1 || imapPort > 65535 || smtpPort < 1 || smtpPort > 65535) throw new DomainException("Los puertos van de 1 a 65535.");
        if (imapHost == null || !imapHost.strip().matches("[A-Za-z0-9.\\-]{1,253}") || smtpHost == null || !smtpHost.strip().matches("[A-Za-z0-9.\\-]{1,253}")) {
            throw new DomainException("Revisa los servidores de correo (ej. imap.hostinger.com).");
        }
        email = email.strip();
        imapHost = imapHost.strip().toLowerCase(Locale.ROOT);
        smtpHost = smtpHost.strip().toLowerCase(Locale.ROOT);
        if (smtpSsl == null) smtpSsl = smtpPort == 465;
    }

    /** La dirección que se muestra y por la que el tablero sabe que es un correo IMAP. */
    public String url() {
        return SCHEME + imapHost + ":" + imapPort;
    }

    public MailAccount withPassword(String other) {
        return new MailAccount(email, other, imapHost, imapPort, smtpHost, smtpPort, smtpSsl);
    }
}
