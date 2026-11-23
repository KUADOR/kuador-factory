package ec.kuador.board.connections.application;

import ec.kuador.board.connections.domain.MxLookup;
import ec.kuador.board.shared.error.DomainException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Con qué conector se conecta un correo, mirando dónde está alojado su dominio (sus registros MX): Microsoft 365 y
 * Outlook.com van por Outlook; Google Workspace y Gmail, por Gmail; el resto (Hostinger, cPanel, Zoho…), por IMAP con
 * sus servidores ya puestos cuando se conocen.
 */
@Component
public class MailDetector {

    /**
     * {@code connector}: OUTLOOK, GMAIL o IMAP. {@code provider}: dónde está el correo, para mostrarlo. Los servidores
     * solo con IMAP; {@code sure} falso si se adivinaron (el hosting no es uno conocido). {@code help} y {@code helpUrl}:
     * qué contraseña pide ese proveedor y dónde se crea (nulos si sirve la del correo).
     */
    public record Detected(String email, String connector, String provider, String imapHost, Integer imapPort, String smtpHost, Integer smtpPort,
            Boolean smtpSsl, boolean sure, String help, String helpUrl) {}

    private record Imap(String provider, String imap, int imapPort, String smtp, int smtpPort, boolean ssl, String help, String helpUrl) {
        Imap(String provider, String imap, int imapPort, String smtp, int smtpPort, boolean ssl) {
            this(provider, imap, imapPort, smtp, smtpPort, ssl, null, null);
        }
    }

    /** Gmail y Google Workspace sin la aplicación de Google de la plataforma: IMAP con contraseña de aplicación. */
    private static final Imap GOOGLE_IMAP = new Imap("Gmail", "imap.gmail.com", 993, "smtp.gmail.com", 465, true,
            "Gmail pide una contraseña de aplicación, no la de la cuenta: en tu cuenta de Google activa la verificación en dos pasos y crea una en "
                    + "«Contraseñas de aplicaciones» (16 letras). En Google Workspace, el administrador tiene que permitirlas.",
            "https://myaccount.google.com/apppasswords");

    private static final Set<String> GMAIL = Set.of("gmail.com", "googlemail.com");
    private static final String YAHOO_HELP = "Yahoo pide una contraseña de aplicación: créala en la seguridad de tu cuenta de Yahoo.";
    private static final String ICLOUD_HELP = "iCloud pide una contraseña específica para apps: créala en tu cuenta de Apple (Inicio de sesión y seguridad).";
    private static final Set<String> OUTLOOK = Set.of("outlook.com", "hotmail.com", "live.com", "msn.com", "outlook.es", "hotmail.es", "live.com.mx",
            "hotmail.com.mx", "outlook.com.mx", "live.com.ar", "hotmail.com.ar", "live.cl", "hotmail.cl", "live.com.ec", "hotmail.co", "live.com.co");
    /** Correos personales conocidos que van por IMAP (con contraseña de aplicación). */
    private static final Map<String, Imap> PERSONAL = Map.of(
            "yahoo.com", new Imap("Yahoo", "imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465, true, YAHOO_HELP, "https://login.yahoo.com/account/security"),
            "yahoo.es", new Imap("Yahoo", "imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465, true, YAHOO_HELP, "https://login.yahoo.com/account/security"),
            "icloud.com", new Imap("iCloud", "imap.mail.me.com", 993, "smtp.mail.me.com", 587, false, ICLOUD_HELP, "https://account.apple.com"),
            "me.com", new Imap("iCloud", "imap.mail.me.com", 993, "smtp.mail.me.com", 587, false, ICLOUD_HELP, "https://account.apple.com"));
    /** Hostings conocidos, por cómo terminan sus servidores MX. */
    private static final List<Map.Entry<String, Imap>> HOSTINGS = List.of(
            Map.entry("hostinger.com", new Imap("Hostinger", "imap.hostinger.com", 993, "smtp.hostinger.com", 465, true)),
            Map.entry("zoho.eu", new Imap("Zoho Mail", "imap.zoho.eu", 993, "smtp.zoho.eu", 465, true)),
            Map.entry("zoho.com", new Imap("Zoho Mail", "imap.zoho.com", 993, "smtp.zoho.com", 465, true)),
            Map.entry("secureserver.net", new Imap("GoDaddy", "imap.secureserver.net", 993, "smtpout.secureserver.net", 465, true)),
            Map.entry("titan.email", new Imap("Titan", "imap.titan.email", 993, "smtp.titan.email", 465, true)),
            Map.entry("ionos.com", new Imap("IONOS", "imap.ionos.com", 993, "smtp.ionos.com", 465, true)),
            Map.entry("yandex.net", new Imap("Yandex", "imap.yandex.com", 993, "smtp.yandex.com", 465, true)));

    private final MxLookup dns;
    private final ConnectorDirectory directory;

    MailDetector(MxLookup dns, ConnectorDirectory directory) {
        this.dns = dns;
        this.directory = directory;
    }

    /** Si Gmail se conecta iniciando sesión (la plataforma tiene su aplicación de Google); si no, va por IMAP. */
    private boolean googleSignIn() {
        return directory.find("GMAIL").map(c -> c.selfRegisters() || directory.client(c).isPresent()).orElse(false);
    }

    private Detected google(String email, String provider) {
        if (googleSignIn()) return oauth(email, "GMAIL", provider);
        Imap i = GOOGLE_IMAP;
        return imap(email, new Imap(provider, i.imap(), i.imapPort(), i.smtp(), i.smtpPort(), i.ssl(), i.help(), i.helpUrl()), true);
    }

    public Detected detect(String email) {
        String mail = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        int at = mail.lastIndexOf('@');
        String domain = at < 0 ? "" : mail.substring(at + 1);
        if (at < 1 || !domain.matches("[a-z0-9\\-]+(\\.[a-z0-9\\-]+)+")) throw new DomainException("Escribe el correo completo (ej. facturas@empresa.com).");
        if (GMAIL.contains(domain)) return google(mail, "Gmail");
        if (OUTLOOK.contains(domain)) return oauth(mail, "OUTLOOK", "Outlook.com");
        if (PERSONAL.containsKey(domain)) return imap(mail, PERSONAL.get(domain), true);
        List<String> mx = dns.mx(domain);
        for (String host : mx) {
            if (host.endsWith(".google.com") || host.endsWith(".googlemail.com")) return google(mail, "Google Workspace");
            if (host.endsWith(".outlook.com")) return oauth(mail, "OUTLOOK", "Microsoft 365");
        }
        for (String host : mx) {
            for (Map.Entry<String, Imap> known : HOSTINGS) {
                if (host.equals(known.getKey()) || host.endsWith("." + known.getKey())) return imap(mail, known.getValue(), true);
            }
        }
        // Hosting propio o desconocido (cPanel, Plesk…): lo habitual es mail.<dominio>. Se muestra para revisarlo.
        String guess = "mail." + domain;
        return imap(mail, new Imap(mx.isEmpty() ? "No se encontró dónde recibe correo este dominio" : "Tu proveedor de correo", guess, 993, guess, 465, true), false);
    }

    private static Detected oauth(String email, String connector, String provider) {
        return new Detected(email, connector, provider, null, null, null, null, null, true, null, null);
    }

    private static Detected imap(String email, Imap i, boolean sure) {
        return new Detected(email, "IMAP", i.provider(), i.imap(), i.imapPort(), i.smtp(), i.smtpPort(), i.ssl(), sure, i.help(), i.helpUrl());
    }
}
